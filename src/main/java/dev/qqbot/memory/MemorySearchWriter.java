/**
 * [INPUT]: 依赖热窗候选、SQLite 作用域和当前 Markdown 正文。
 * [OUTPUT]: 复核来源后原子替换同源分块、词项和引用记录。
 * [POS]: 词法索引写边界。分词和标识计算在发布事务之前完成。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.RetentionProperties;
import dev.qqbot.persistence.Db;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

final class MemorySearchWriter {
    private static final String DELETE_SOURCE =
            "delete from memory_search_chunk where group_id=? and epoch=?";
    private final Db db;
    private final MarkdownMemory files;
    private final Clock clock;
    private final RetentionProperties retention;

    MemorySearchWriter(Db db, MarkdownMemory files, Clock clock, RetentionProperties retention) {
        this.db = db;
        this.files = files;
        this.clock = clock;
        this.retention = retention;
    }

    boolean replace(Scope scope, Source source) {
        var prepared = prepare(scope, source);
        return db.tx(() -> publish(scope, prepared));
    }

    private static Prepared prepare(Scope scope, Source source) {
        var parts = new ArrayList<Part>();
        var chunks = SearchText.chunks(source.body());
        for (int index = 0; index < chunks.size(); index++) {
            String body = chunks.get(index);
            UUID id = chunkId(scope, source.kind(), source.key(), index);
            parts.add(new Part(id, index, body, SearchText.terms(body, Integer.MAX_VALUE)));
        }
        // 内容哈希覆盖来源正文，用于发现同源正文修改及跳过重复写入。
        return new Prepared(source, MemberLabels.hash(source.body()), List.copyOf(parts));
    }

    static UUID chunkId(Scope scope, SourceKind kind, String sourceKey, int index) {
        // 身份哈希覆盖群、代际、来源种类、来源键和分块号，用于稳定关联索引行。
        String key =
                scope.groupId() + ":" + scope.epoch() + ":" + kind + ":" + sourceKey + ":" + index;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private boolean publish(Scope scope, Prepared prepared) {
        Source source = prepared.source();
        if (!isActive(scope) || !isValid(scope, source)) {
            return false;
        }
        if (alreadyIndexed(scope, prepared)) {
            return true;
        }
        // SIDE EFFECT: 分块、词项和来源引用共用事务，写入失败时恢复旧索引。
        deletePrevious(scope, source);
        for (Part part : prepared.parts()) {
            insertPart(scope, prepared, part);
        }
        return true;
    }

    boolean isActive(Scope scope) {
        return db.find(
                        "select 1 from group_config where id=? and epoch=? and enabled",
                        (row, index) -> true,
                        scope.groupId(),
                        scope.epoch())
                .isPresent();
    }

    private boolean alreadyIndexed(Scope scope, Prepared prepared) {
        Source source = prepared.source();
        var existing =
                db.find(
                        """
                select content_hash,count(*) from memory_search_chunk where group_id=? and epoch=?
                  and source_kind=? and source_key=? group by content_hash
                """,
                        (row, index) -> new Existing(row.getString(1), row.getInt(2)),
                        scope.groupId(),
                        scope.epoch(),
                        source.kind().name(),
                        source.key());
        return existing.filter(value -> value.matches(prepared)).isPresent();
    }

    private void deletePrevious(Scope scope, Source source) {
        if (source.kind() == SourceKind.MANUAL) {
            db.exec(DELETE_SOURCE + " and source_kind='MANUAL'", scope.groupId(), scope.epoch());
        } else {
            db.exec(
                    DELETE_SOURCE + " and source_kind=? and source_key=?",
                    scope.groupId(),
                    scope.epoch(),
                    source.kind().name(),
                    source.key());
        }
    }

    private void insertPart(Scope scope, Prepared prepared, Part part) {
        Source source = prepared.source();
        db.exec(
                """
                insert into memory_search_chunk(id,group_id,epoch,source_kind,source_key,part_index,
                  body,content_hash,source_time,indexed_at,document_id) values(?,?,?,?,?,?,?,?,?,?,?)
                """,
                part.id(),
                scope.groupId(),
                scope.epoch(),
                source.kind().name(),
                source.key(),
                part.index(),
                part.body(),
                prepared.bodyHash(),
                source.origin().time(),
                clock.instant(),
                source.origin().documentId());
        insertTerms(scope.groupId(), part);
        insertSources(part.id(), source.origin().messageIds());
    }

    private void insertTerms(UUID groupId, Part part) {
        if (!part.terms().isEmpty()) {
            var terms = new ArrayList<Object[]>();
            for (String term : part.terms()) {
                terms.add(new Object[] {part.id().toString(), groupId.toString(), term});
            }
            db.jdbc.batchUpdate(
                    "insert into memory_search_term(chunk_id,group_id,term) values(?,?,?)", terms);
        }
    }

    private void insertSources(UUID partId, List<UUID> messageIds) {
        if (!messageIds.isEmpty()) {
            var originals = new ArrayList<Object[]>();
            for (UUID messageId : messageIds) {
                originals.add(new Object[] {partId.toString(), messageId.toString()});
            }
            db.jdbc.batchUpdate(
                    "insert into memory_search_source(chunk_id,message_id) values(?,?)", originals);
        }
    }

    private boolean isValid(Scope scope, Source source) {
        return switch (source.kind()) {
            case MANUAL ->
                    MemberLabels.hash(files.read(scope.groupId()))
                            .equals(MemberLabels.hash(source.body()));
            case RAW -> isRawCurrent(scope, source);
            case SUMMARY -> isSummaryCurrent(scope, source.origin());
            case VECTOR -> false;
        };
    }

    private boolean isRawCurrent(Scope scope, Source source) {
        return db.find(
                        """
                        select body from group_message where id=? and group_id=? and epoch=? and kind='TEXT'
                          and event_seq is not null and occurred_at>=?
                        """,
                        (row, index) -> row.getString(1),
                        source.origin().messageIds().get(0),
                        scope.groupId(),
                        scope.epoch(),
                        retention.hotCutoff(clock.instant()))
                .filter(source.body()::equals)
                .isPresent();
    }

    private boolean isSummaryCurrent(Scope scope, Origin origin) {
        boolean isReady =
                db.find(
                                """
                select 1 from memory_document where id=? and group_id=? and epoch=? and kind='SUMMARY'
                  and state='READY' and source_count=?
                """,
                                (row, index) -> true,
                                origin.documentId(),
                                scope.groupId(),
                                scope.epoch(),
                                origin.messageIds().size())
                        .isPresent();
        if (!isReady) {
            return false;
        }
        var current =
                db.query(
                        """
                select m.id from memory_document_source s join group_message m on m.id=s.message_id
                where s.document_id=? and m.group_id=? and m.epoch=? and m.kind='TEXT'
                  and m.event_seq is not null and m.occurred_at>=? order by m.id
                """,
                        (row, index) -> UUID.fromString(row.getString(1)),
                        origin.documentId(),
                        scope.groupId(),
                        scope.epoch(),
                        retention.hotCutoff(clock.instant()));
        return current.size() == origin.messageIds().size()
                && new HashSet<>(current).equals(new HashSet<>(origin.messageIds()));
    }

    record Scope(UUID groupId, long epoch) {}

    record Source(SourceKind kind, String key, String body, Origin origin) {}

    record Origin(Instant time, UUID documentId, List<UUID> messageIds) {
        Origin {
            messageIds = List.copyOf(messageIds);
        }
    }

    private record Prepared(Source source, String bodyHash, List<Part> parts) {}

    private record Part(UUID id, int index, String body, List<String> terms) {}

    private record Existing(String hash, int partCount) {
        boolean matches(Prepared prepared) {
            return hash.equals(prepared.bodyHash()) && partCount == prepared.parts().size();
        }
    }
}
