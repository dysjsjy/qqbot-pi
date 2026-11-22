/**
 * [INPUT]: 依赖 Db、EmbeddingClient、会话范围和热窗水位。
 * [OUTPUT]: 事务内发布有效的向量分块，按相似度读取限量热区证据。
 * [POS]: 嵌入在事务外执行；发布与正文读取均复核当前群范围。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.BotProperties;
import dev.qqbot.RetentionProperties;
import dev.qqbot.persistence.Db;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class VectorMemory {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(VectorMemory.class);
    private static final int MAX_INDEX_BATCH = 100;
    private static final int MAX_SEARCH_RESULTS = 10;
    private static final int LEGACY_CHUNK_CODE_POINTS = 1_200;
    private static final String EMBEDDING_FAILED = "EMBEDDING_FAILED";
    private static final String EMBEDDING_COUNT_MISMATCH = "EMBEDDING_COUNT_MISMATCH";
    private static final String SCOPE_FILTER =
            """
            g.enabled and m.group_id=? and m.epoch=? and m.kind='TEXT'
            and m.event_seq is not null and m.event_seq<? and m.occurred_at<=? and m.occurred_at>=?
            """;
    private final Db db;
    private final EmbeddingClient embeddings;
    private final BotProperties properties;
    private final Clock clock;
    private final RetentionProperties retention;

    public VectorMemory(Db db, EmbeddingClient embeddings, BotProperties properties, Clock clock) {
        this(db, embeddings, properties, clock, RetentionProperties.defaults());
    }

    @Autowired
    public VectorMemory(
            Db db,
            EmbeddingClient embeddings,
            BotProperties properties,
            Clock clock,
            RetentionProperties retention) {
        this.db = db;
        this.embeddings = embeddings;
        this.properties = properties;
        this.clock = clock;
        this.retention = retention;
    }

    public VectorMemory(Db db, EmbeddingClient embeddings, BotProperties properties) {
        this(db, embeddings, properties, Clock.systemUTC());
    }

    public int indexPending(int limit) {
        if (!properties.ragEnabled()) {
            return 0;
        }
        var pending =
                db.query(
                        """
                select m.id,m.group_id,m.epoch,m.body from group_message m
                join group_config g on g.id=m.group_id and g.epoch=m.epoch
                where m.index_state='PENDING' and m.kind='TEXT' and m.event_seq is not null
                    and m.occurred_at>=? and g.enabled
                order by m.received_at,m.id limit ?
                """,
                        (row, index) ->
                                new PendingMessage(
                                        UUID.fromString(row.getString(1)),
                                                UUID.fromString(row.getString(2)),
                                        row.getLong(3), row.getString(4)),
                        retention.hotCutoff(clock.instant()),
                        Math.min(MAX_INDEX_BATCH, Math.max(0, limit)));
        int completed = 0;
        for (PendingMessage message : pending) {
            try {
                indexMessage(message);
                completed++;
            } catch (RuntimeException failure) {
                recordIndexFailure(message, failure);
            }
        }
        return completed;
    }

    private void indexMessage(PendingMessage message) {
        List<String> chunks = SearchText.chunks(message.body());
        if (chunks.isEmpty()) {
            markIndexed(message.id());
            return;
        }
        // SIDE EFFECT: 不在 SQLite 事务内等待远程嵌入。
        List<float[]> vectors = embeddings.embed(chunks);
        db.tx(() -> publishVectors(message, chunks, vectors));
    }

    private Void publishVectors(
            PendingMessage message, List<String> chunks, List<float[]> vectors) {
        requireCurrentSource(message);
        requireVectorCount(chunks.size(), vectors.size(), "messageId=" + message.id());
        var encoded = vectors.stream().map(EmbeddingClient::vector).toList();

        // SIDE EFFECT: 分块和索引状态由调用方事务一起提交或回滚。
        for (int part = 0; part < chunks.size(); part++) {
            db.exec(
                    """
                    insert into memory_chunk(id,group_id,message_id,epoch,part_index,body,embedding,model,dimensions)
                    values(?,?,?,?,?,?,?,?,?) on conflict(message_id,part_index,model) do nothing
                    """,
                    chunkId(message.id(), part),
                    message.groupId(),
                    message.id(),
                    message.epoch(),
                    part,
                    chunks.get(part),
                    encoded.get(part),
                    properties.embedding().model(),
                    properties.embedding().dimensions());
        }
        markIndexed(message.id());
        return null;
    }

    private void requireCurrentSource(PendingMessage message) {
        boolean isCurrent =
                db.find(
                                """
                select 1 from group_message m join group_config g on g.id=m.group_id and g.epoch=m.epoch
                where g.enabled and m.id=? and m.group_id=? and m.epoch=? and m.occurred_at>=?
                """,
                                (row, index) -> true,
                                message.id(),
                                message.groupId(),
                                message.epoch(),
                                retention.hotCutoff(clock.instant()))
                        .isPresent();
        if (!isCurrent) {
            throw new IllegalStateException(
                    "MEMORY_EPOCH_CHANGED: messageId="
                            + message.id()
                            + ", groupId="
                            + message.groupId()
                            + ", epoch="
                            + message.epoch());
        }
    }

    private void markIndexed(UUID messageId) {
        // SIDE EFFECT: 空分块也要退出待索引队列，避免重复扫描。
        db.exec(
                """
                update group_message set index_state='INDEXED',index_attempts=index_attempts+1,error_code=null
                where id=?
                """,
                messageId);
    }

    private void recordIndexFailure(PendingMessage message, RuntimeException failure) {
        log.warn(
                "EMBEDDING_FAILED group={} message={} exception={}",
                message.groupId(),
                message.id(),
                failure.getClass().getSimpleName());
        // SIDE EFFECT: 只对仍在热窗中的待索引消息记账。
        db.exec(
                """
                update group_message set index_attempts=index_attempts+1,
                    index_state=case when index_attempts+1>=3 then 'FAILED' else 'PENDING' end,error_code=?
                where id=? and index_state='PENDING' and occurred_at>=?
                """,
                EMBEDDING_FAILED,
                message.id(),
                retention.hotCutoff(clock.instant()));
    }

    private static void requireVectorCount(int expected, int actual, String target) {
        if (actual != expected) {
            throw new IllegalStateException(
                    EMBEDDING_COUNT_MISMATCH,
                    new IllegalArgumentException(
                            target + ", expected=" + expected + ", actual=" + actual));
        }
    }

    public List<MemorySource> search(UUID groupId, String question, int limit, UUID exclude) {
        var group = db.one("select epoch,enabled,event_seq from group_config where id=?", groupId);
        if (group == null || !Boolean.TRUE.equals(group.get("enabled"))) {
            throw new IllegalArgumentException("GROUP_DISABLED");
        }
        long epoch = ((Number) group.get("epoch")).longValue();
        var origin =
                exclude == null
                        ? null
                        : db.one(
                                "select event_seq,occurred_at from group_message where id=? and group_id=? and epoch=?",
                                exclude,
                                groupId,
                                epoch);
        if (exclude != null && (origin == null || origin.get("event_seq") == null)) {
            return List.of();
        }
        Instant now = clock.instant();
        long before =
                origin == null
                        ? Math.addExact(((Number) group.get("event_seq")).longValue(), 1)
                        : ((Number) origin.get("event_seq")).longValue();
        var scope =
                new MemoryScope(
                        groupId,
                        epoch,
                        exclude,
                        before,
                        origin == null ? now : ((Timestamp) origin.get("occurred_at")).toInstant(),
                        now);
        return search(scope, question, limit).evidence().stream()
                .map(
                        item ->
                                new MemorySource(
                                        item.messageIds().get(0).toString(),
                                        "历史原文",
                                        item.occurredAt(),
                                        item.body(),
                                        item.score()))
                .toList();
    }

    public MemorySearchIndex.Matches search(MemoryScope scope, String question, int requested) {
        int limit = Math.min(MAX_SEARCH_RESULTS, Math.max(0, requested));
        List<String> terms = SearchText.queryTerms(question == null ? "" : question);
        if (limit == 0 || !properties.ragEnabled() || terms.isEmpty() || !active(scope)) {
            return new MemorySearchIndex.Matches(List.of(), 0);
        }
        // SIDE EFFECT: 只有当前群的非空检索词才发起嵌入请求。
        var embedded = embeddings.embed(List.of(question));
        requireVectorCount(1, embedded.size(), "groupId=" + scope.groupId());
        List<RankedChunk> ranked = topMessages(rankChunks(scope, embedded.get(0)), limit);
        if (ranked.isEmpty()) {
            return new MemorySearchIndex.Matches(List.of(), 0);
        }
        var bodies = readBodies(scope, ranked);
        var evidence = new ArrayList<Evidence>();
        for (RankedChunk chunk : ranked) {
            var excerpt = findEvidence(chunk, bodies.get(chunk.messageId()), terms);
            if (excerpt.isPresent()) {
                evidence.add(excerpt.orElseThrow());
            }
        }
        return new MemorySearchIndex.Matches(evidence, bodies.size());
    }

    private static List<RankedChunk> topMessages(List<RankedChunk> metadata, int limit) {
        var ranked = new ArrayList<RankedChunk>();
        var seen = new HashSet<UUID>();
        for (RankedChunk item : metadata) {
            if (seen.add(item.messageId())) {
                ranked.add(item);
            }
            if (ranked.size() >= limit) {
                break;
            }
        }
        return ranked;
    }

    private Map<UUID, String> readBodies(MemoryScope scope, List<RankedChunk> ranked) {
        String placeholders = String.join(",", Collections.nCopies(ranked.size(), "?"));
        var args = new ArrayList<Object>();
        args.addAll(ranked.stream().map(RankedChunk::messageId).toList());
        args.addAll(scopeArgs(scope));
        var bodies =
                db.list(
                        """
                        select m.id,m.body from group_message m
                        join group_config g on g.id=m.group_id and g.epoch=m.epoch where m.id in (
                        """
                                + placeholders
                                + ") and "
                                + SCOPE_FILTER,
                        args.toArray());
        var content = new HashMap<UUID, String>();
        for (var row : bodies) {
            content.put((UUID) row.get("id"), (String) row.get("body"));
        }
        return content;
    }

    private Optional<Evidence> findEvidence(RankedChunk chunk, String body, List<String> terms) {
        if (body == null) {
            return Optional.empty();
        }
        List<String> parts = SearchText.chunks(body);
        UUID messageId = chunk.messageId();
        int stored = chunk.part();
        int part =
                chunkId(messageId, stored).equals(chunk.id())
                        ? stored
                        : legacyPart(parts, stored, terms);
        if (part < 0 || part >= parts.size()) {
            return Optional.empty();
        }
        return Optional.of(
                new Evidence(
                        "message:" + messageId + ":" + part,
                        SourceKind.VECTOR,
                        parts.get(part),
                        List.of(messageId),
                        chunk.occurredAt(),
                        chunk.score()));
    }

    private boolean active(MemoryScope scope) {
        var group = db.one("select enabled,epoch from group_config where id=?", scope.groupId());
        return group != null
                && Boolean.TRUE.equals(group.get("enabled"))
                && ((Number) group.get("epoch")).longValue() == scope.epoch();
    }

    private List<Object> scopeArgs(MemoryScope scope) {
        return List.of(
                scope.groupId(),
                scope.epoch(),
                scope.beforeSeq(),
                scope.occurredBeforeOrAt(),
                retention.hotCutoff(scope.evaluatedAt()));
    }

    private List<RankedChunk> rankChunks(MemoryScope scope, float[] query) {
        var args = new ArrayList<Object>(scopeArgs(scope));
        args.add(properties.embedding().model());
        args.add(query.length);
        var rows =
                db.list(
                        """
                        select c.id,c.message_id,c.part_index,m.occurred_at,c.embedding from memory_chunk c
                        join group_message m on m.id=c.message_id and m.group_id=c.group_id and m.epoch=c.epoch
                        join group_config g on g.id=m.group_id and g.epoch=m.epoch where
                        """
                                + SCOPE_FILTER
                                + " and c.model=? and c.dimensions=?",
                        args.toArray());
        var found = new ArrayList<RankedChunk>();
        for (var row : rows) {
            float[] vector = db.parse(row.get("embedding").toString(), float[].class);
            double score = cosine(query, vector);
            if (Double.isFinite(score) && score >= properties.embedding().minSimilarity()) {
                found.add(
                        new RankedChunk(
                                (UUID) row.get("id"),
                                (UUID) row.get("message_id"),
                                ((Number) row.get("part_index")).intValue(),
                                ((Timestamp) row.get("occurred_at")).toInstant(),
                                score));
            }
        }
        found.sort(
                Comparator.comparingDouble(RankedChunk::score)
                        .reversed()
                        .thenComparing(RankedChunk::occurredAt, Comparator.reverseOrder())
                        .thenComparing(chunk -> chunk.messageId().toString())
                        .thenComparingInt(RankedChunk::part));
        return found;
    }

    private static double cosine(float[] query, float[] vector) {
        if (query.length != vector.length) {
            return Double.NaN;
        }
        double dot = 0;
        double left = 0;
        double right = 0;
        for (int index = 0; index < query.length; index++) {
            dot += (double) query[index] * vector[index];
            left += (double) query[index] * query[index];
            right += (double) vector[index] * vector[index];
        }
        return left == 0 || right == 0 ? Double.NaN : dot / Math.sqrt(left * right);
    }

    private UUID chunkId(UUID messageId, int part) {
        // 分块身份覆盖消息、嵌入模型和分块序号，不用于校验正文。
        return UUID.nameUUIDFromBytes(
                ("memory-vector-v2:"
                                + messageId
                                + ":"
                                + properties.embedding().model()
                                + ":"
                                + part)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static int legacyPart(List<String> parts, int stored, List<String> terms) {
        long start = (long) stored * LEGACY_CHUNK_CODE_POINTS;
        long end = start + LEGACY_CHUNK_CODE_POINTS;
        long offset = 0;
        int best = -1;
        double highest = -1;
        for (int part = 0; part < parts.size(); part++) {
            long next = offset + parts.get(part).codePointCount(0, parts.get(part).length());
            if (offset < end && next > start) {
                double score = SearchText.score(parts.get(part), terms);
                if (score > highest) {
                    best = part;
                    highest = score;
                }
            }
            offset = next;
        }
        return best;
    }

    private record PendingMessage(UUID id, UUID groupId, long epoch, String body) {}

    private record RankedChunk(
            UUID id, UUID messageId, int part, Instant occurredAt, double score) {}
}
