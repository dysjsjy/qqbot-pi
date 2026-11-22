/**
 * [INPUT]: 依赖当前群、热窗口、原文、摘要与手工记忆。
 * [OUTPUT]: 维护词法索引水位，提供限定正文读取数的查询。
 * [POS]: 词法索引门面。写入委托 MemorySearchWriter，查询委托 LexicalMemorySearch。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.RetentionProperties;
import dev.qqbot.memory.MemorySearchWriter.Origin;
import dev.qqbot.memory.MemorySearchWriter.Scope;
import dev.qqbot.memory.MemorySearchWriter.Source;
import dev.qqbot.persistence.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MemorySearchIndex {
    private static final Logger LOG = LoggerFactory.getLogger(MemorySearchIndex.class);
    private static final int MAX_INDEX_BATCH = 100;
    private static final int MAX_BODY_READS = 200;
    private static final Comparator<Evidence> ORDER =
            Comparator.comparingDouble(Evidence::score)
                    .reversed()
                    .thenComparing(Evidence::occurredAt, Comparator.reverseOrder())
                    .thenComparing(Evidence::sourceKey);
    private final Db db;
    private final MarkdownMemory files;
    private final Clock clock;
    private final RetentionProperties retention;
    private final MemorySearchWriter writer;
    private final LexicalMemorySearch lexical;

    public MemorySearchIndex(Db db, MarkdownMemory files, Clock clock) {
        this(db, files, clock, RetentionProperties.defaults());
    }

    @Autowired
    public MemorySearchIndex(
            Db db, MarkdownMemory files, Clock clock, RetentionProperties retention) {
        this.db = db;
        this.files = files;
        this.clock = clock;
        this.retention = retention;
        this.writer = new MemorySearchWriter(db, files, clock, retention);
        this.lexical = new LexicalMemorySearch(db, files, retention);
    }

    public record Matches(List<Evidence> evidence, int bodiesRead) {
        public Matches {
            evidence = List.copyOf(evidence);
        }
    }

    /** 检查群已启用且仍处于请求代际。 */
    public boolean active(MemoryScope scope) {
        return writer.isActive(new Scope(scope.groupId(), scope.epoch()));
    }

    /** 按手工记忆、原文和摘要的顺序推进索引，返回本轮完成数。 */
    public int indexPending(UUID groupId, int requested) {
        int limit = Math.min(MAX_INDEX_BATCH, Math.max(0, requested));
        if (limit == 0) {
            return 0;
        }
        var cursor = db.tx(() -> initialize(groupId));
        return cursor.map(value -> indexBatch(value, limit)).orElse(0);
    }

    private Optional<Cursor> initialize(UUID groupId) {
        var epoch =
                db.find(
                        "select epoch from group_config where id=? and enabled",
                        (row, index) -> row.getLong(1),
                        groupId);
        if (epoch.isEmpty()) {
            return Optional.empty();
        }
        long activeEpoch = epoch.orElseThrow();
        var cursor =
                db.find(
                        "select epoch,manual_hash,last_message_seq from memory_search_state where group_id=?",
                        (row, index) -> readCursor(groupId, row),
                        groupId);
        if (cursor.isPresent()) {
            Cursor previous = cursor.orElseThrow();
            if (previous.scope().epoch() == activeEpoch) {
                return cursor;
            }
            clearRows(groupId);
        }
        // SIDE EFFECT: 索引状态与代际重置由初始化事务一起提交。
        db.exec(
                "insert into memory_search_state(group_id,epoch,updated_at) values(?,?,?)",
                groupId,
                activeEpoch,
                clock.instant());
        return Optional.of(new Cursor(new Scope(groupId, activeEpoch), null, 0));
    }

    private static Cursor readCursor(UUID groupId, ResultSet row) throws SQLException {
        return new Cursor(new Scope(groupId, row.getLong(1)), row.getString(2), row.getLong(3));
    }

    private int indexBatch(Cursor cursor, int limit) {
        int completed = indexManual(cursor);
        if (completed >= limit) {
            return completed;
        }
        Instant hotCutoff = retention.hotCutoff(clock.instant());
        var messages = indexMessages(cursor, hotCutoff, limit - completed);
        completed += messages.completed();
        if (messages.isBlocked() || completed >= limit) {
            return completed;
        }
        return completed + indexSummaries(cursor.scope(), hotCutoff, limit - completed);
    }

    private int indexManual(Cursor cursor) {
        Scope scope = cursor.scope();
        String manual = files.read(scope.groupId());
        // 正文哈希区分手工记忆版本，并使查询忽略未重建的旧分块。
        String hash = MemberLabels.hash(manual);
        if (hash.equals(cursor.manualHash())) {
            return 0;
        }
        var source =
                new Source(
                        SourceKind.MANUAL,
                        "manual:" + hash,
                        manual,
                        new Origin(Instant.EPOCH, null, List.of()));
        if (!writer.replace(scope, source)) {
            return 0;
        }
        db.exec(
                "update memory_search_state set manual_hash=?,updated_at=? where group_id=? and epoch=?",
                hash,
                clock.instant(),
                scope.groupId(),
                scope.epoch());
        return !manual.isEmpty() || cursor.manualHash() != null ? 1 : 0;
    }

    private MessageBatch indexMessages(Cursor cursor, Instant hotCutoff, int limit) {
        Scope scope = cursor.scope();
        var messages =
                db.query(
                        """
                select id,event_seq,kind,body,occurred_at from group_message
                where group_id=? and epoch=? and event_seq>? and occurred_at>=? order by event_seq limit ?
                """,
                        IndexedMessage::read,
                        scope.groupId(),
                        scope.epoch(),
                        cursor.sequence(),
                        hotCutoff,
                        limit);
        int completed = 0;
        for (IndexedMessage message : messages) {
            if (message.isText() && !writer.replace(scope, message.source())) {
                return new MessageBatch(completed, true);
            }
            advance(scope, message.sequence());
            completed++;
        }
        return new MessageBatch(completed, false);
    }

    private void advance(Scope scope, long sequence) {
        db.exec(
                "update memory_search_state set last_message_seq=?,updated_at=? where group_id=? and epoch=?",
                sequence,
                clock.instant(),
                scope.groupId(),
                scope.epoch());
    }

    private int indexSummaries(Scope scope, Instant hotCutoff, int limit) {
        List<UUID> ids =
                db.query(
                        """
                select d.id from memory_document d
                where d.group_id=? and d.epoch=? and d.kind='SUMMARY' and d.state='READY' and d.source_count>0
                  and not exists(select 1 from memory_search_chunk c where c.document_id=d.id)
                  and (select count(*) from memory_document_source s where s.document_id=d.id)=d.source_count
                  and not exists(select 1 from memory_document_source s left join group_message m on m.id=s.message_id
                    where s.document_id=d.id and (m.id is null or m.group_id<>d.group_id or m.epoch<>d.epoch
                      or m.event_seq is null or m.occurred_at<?))
                order by d.created_at,d.id limit ?
                """,
                        (row, index) -> UUID.fromString(row.getString(1)),
                        scope.groupId(),
                        scope.epoch(),
                        hotCutoff,
                        limit);
        int completed = 0;
        for (UUID id : ids) {
            if (indexSummary(scope, id)) {
                completed++;
            }
        }
        return completed;
    }

    private boolean indexSummary(Scope scope, UUID id) {
        String content = files.readDocument(scope.groupId(), id, "SUMMARY");
        if (content.isBlank()) {
            LOG.warn("MEMORY_DOCUMENT_MISSING group={} document={}", scope.groupId(), id);
            return false;
        }
        var originals =
                db.query(
                        """
                select m.id,m.occurred_at from group_message m
                join memory_document_source s on s.message_id=m.id where s.document_id=? order by m.event_seq
                """,
                        (row, index) -> DocumentMessage.read(row),
                        id);
        var ids = originals.stream().map(DocumentMessage::id).toList();
        var at =
                originals.stream()
                        .map(DocumentMessage::at)
                        .max(Instant::compareTo)
                        .orElse(Instant.EPOCH);
        return writer.replace(
                scope,
                new Source(SourceKind.SUMMARY, "summary:" + id, content, new Origin(at, id, ids)));
    }

    /** 删除群的词法索引和增量水位。 */
    public void clearIndex(UUID groupId) {
        db.tx(() -> clearRows(groupId));
    }

    private Void clearRows(UUID groupId) {
        db.exec("delete from memory_search_chunk where group_id=?", groupId);
        db.exec("delete from memory_search_state where group_id=?", groupId);
        return null;
    }

    /** 重新标记群索引，下一轮从头构建。 */
    public void rebuild(UUID groupId) {
        clearIndex(groupId);
    }

    /** 在指定的正文读取预算内查找词法证据。 */
    public Matches lexical(MemoryScope scope, String question, QueryMode mode, int bodyBudget) {
        int budget = Math.max(0, Math.min(MAX_BODY_READS, bodyBudget));
        List<String> terms = SearchText.queryTerms(question);
        if (terms.isEmpty() || budget == 0 || !active(scope)) {
            return new Matches(List.of(), 0);
        }
        return lexical.search(scope, terms, mode, budget);
    }

    /** 按分数排序，相同来源或原文只保留最高分。 */
    public static List<Evidence> unique(List<Evidence> candidates, int limit) {
        var result = new ArrayList<Evidence>();
        var messages = new HashSet<UUID>();
        var keys = new HashSet<String>();
        for (var candidate : candidates.stream().sorted(ORDER).toList()) {
            if (keys.contains(candidate.sourceKey())
                    || candidate.messageIds().stream().anyMatch(messages::contains)) {
                continue;
            }
            result.add(candidate);
            keys.add(candidate.sourceKey());
            messages.addAll(candidate.messageIds());
            if (result.size() >= limit) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private record Cursor(Scope scope, String manualHash, long sequence) {}

    private record MessageBatch(int completed, boolean isBlocked) {}

    private record IndexedMessage(long sequence, boolean isText, Source source) {
        static IndexedMessage read(ResultSet row, int index) throws SQLException {
            long sequence = row.getLong(2);
            if (!"TEXT".equals(row.getString(3))) {
                return new IndexedMessage(sequence, false, null);
            }
            UUID id = UUID.fromString(row.getString(1));
            var origin = new Origin(Instant.ofEpochMilli(row.getLong(5)), null, List.of(id));
            return new IndexedMessage(
                    sequence,
                    true,
                    new Source(SourceKind.RAW, "message:" + id, row.getString(4), origin));
        }
    }

    private record DocumentMessage(UUID id, Instant at) {
        static DocumentMessage read(ResultSet row) throws SQLException {
            return new DocumentMessage(
                    UUID.fromString(row.getString(1)), Instant.ofEpochMilli(row.getLong(2)));
        }
    }
}
