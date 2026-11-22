/**
 * [INPUT]: 依赖有界查询词、当前会话范围、手工记忆版本和热窗口。
 * [OUTPUT]: 返回已校验的词法证据及实际读取的正文行数。
 * [POS]: 词法索引读取侧。历史模式在剩余预算内补读尚未索引的原文。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.RetentionProperties;
import dev.qqbot.persistence.Db;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

final class LexicalMemorySearch {
    private static final int MAX_EVIDENCE = 10;
    private static final int MIN_CHAT_HITS = 2;
    private static final double MIN_CHAT_COVERAGE = 0.5;
    private static final String INDEXED_PREFIX =
            """
            select c.id,c.source_kind,c.source_key,c.part_index,c.body,c.source_time,count(distinct t.term) as hits
            from memory_search_term t join memory_search_chunk c on c.id=t.chunk_id
            join group_config g on g.id=c.group_id and g.epoch=c.epoch and g.enabled
            where t.group_id=? and t.term in (
            """;
    private static final String INDEXED_SUFFIX =
            """
            ) and c.group_id=? and c.epoch=? and (
              (c.source_kind='MANUAL' and c.source_key=?
                and not exists(select 1 from memory_search_source s where s.chunk_id=c.id))
              or (c.source_kind in ('RAW','SUMMARY')
                and ((c.source_kind='RAW' and (select count(*) from memory_search_source s where s.chunk_id=c.id)=1)
                  or (c.source_kind='SUMMARY' and exists(select 1 from memory_document d
                    where d.id=c.document_id and d.group_id=c.group_id and d.epoch=c.epoch
                      and d.kind='SUMMARY' and d.state='READY' and d.source_count>0
                      and (select count(*) from memory_search_source s where s.chunk_id=c.id)=d.source_count
                      and (select count(*) from memory_document_source ds where ds.document_id=d.id)=d.source_count
                      and not exists(select 1 from memory_search_source s where s.chunk_id=c.id
                        and not exists(select 1 from memory_document_source ds
                          where ds.document_id=d.id and ds.message_id=s.message_id)))))
                and not exists(select 1 from memory_search_source s left join group_message m on m.id=s.message_id
                  where s.chunk_id=c.id and (m.id is null or m.group_id<>? or m.epoch<>? or m.kind<>'TEXT'
                    or m.event_seq is null or m.event_seq>=? or m.occurred_at>? or m.occurred_at<?))))
            group by c.id order by hits desc,c.source_time desc,c.source_key||':'||cast(c.part_index as text) limit ?
            """;
    private final Db db;
    private final MarkdownMemory files;
    private final RetentionProperties retention;

    LexicalMemorySearch(Db db, MarkdownMemory files, RetentionProperties retention) {
        this.db = db;
        this.files = files;
        this.retention = retention;
    }

    MemorySearchIndex.Matches search(
            MemoryScope scope, List<String> terms, QueryMode mode, int budget) {
        var indexed = readIndexed(scope, terms, budget);
        var sourceIds = readSourceIds(indexed);
        List<Evidence> found = toEvidence(indexed, terms.size(), mode, sourceIds);
        int read = indexed.size();
        if (mode == QueryMode.HISTORY && read < budget) {
            var pending = readPending(scope, budget - read);
            for (RawMatch message : pending) {
                found.addAll(scorePending(message, terms));
            }
            read += pending.size();
        }
        return new MemorySearchIndex.Matches(MemorySearchIndex.unique(found, MAX_EVIDENCE), read);
    }

    private List<Chunk> readIndexed(MemoryScope scope, List<String> terms, int budget) {
        String manualKey = "manual:" + MemberLabels.hash(files.read(scope.groupId()));
        String sql = INDEXED_PREFIX + placeholders(terms.size()) + INDEXED_SUFFIX;
        var args = new ArrayList<Object>();
        args.add(scope.groupId());
        args.addAll(terms);
        args.add(scope.groupId());
        args.add(scope.epoch());
        args.add(manualKey);
        args.addAll(
                List.of(
                        scope.groupId(),
                        scope.epoch(),
                        scope.beforeSeq(),
                        scope.occurredBeforeOrAt(),
                        retention.hotCutoff(scope.evaluatedAt()),
                        budget));
        return db.list(sql, args.toArray()).stream().map(Chunk::from).toList();
    }

    private List<RawMatch> readPending(MemoryScope scope, int limit) {
        return db
                .list(
                        """
                select m.id,m.body,m.occurred_at from group_message m
                join group_config g on g.id=m.group_id and g.epoch=m.epoch and g.enabled
                where m.group_id=? and m.epoch=? and m.kind='TEXT' and m.event_seq<?
                  and m.occurred_at<=? and m.occurred_at>=?
                  and not exists(select 1 from memory_search_chunk c where c.group_id=m.group_id and c.epoch=m.epoch
                    and c.source_kind='RAW' and c.source_key='message:'||cast(m.id as text)
                    and exists(select 1 from memory_search_source s where s.chunk_id=c.id and s.message_id=m.id))
                order by m.event_seq desc limit ?
                """,
                        scope.groupId(),
                        scope.epoch(),
                        scope.beforeSeq(),
                        scope.occurredBeforeOrAt(),
                        retention.hotCutoff(scope.evaluatedAt()),
                        limit)
                .stream()
                .map(RawMatch::from)
                .toList();
    }

    private Map<UUID, List<UUID>> readSourceIds(List<Chunk> chunks) {
        if (chunks.isEmpty()) {
            return Map.of();
        }
        String sql =
                "select chunk_id,message_id from memory_search_source where chunk_id in ("
                        + placeholders(chunks.size())
                        + ") order by chunk_id,message_id";
        var rows =
                db.query(
                        sql,
                        (row, index) -> SourceLink.read(row),
                        chunks.stream().map(Chunk::id).toArray());
        var result = new HashMap<UUID, List<UUID>>();
        for (var row : rows) {
            result.computeIfAbsent(row.chunkId(), id -> new ArrayList<>()).add(row.messageId());
        }
        return result;
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static List<Evidence> toEvidence(
            List<Chunk> indexed, int termCount, QueryMode mode, Map<UUID, List<UUID>> sourceIds) {
        List<Evidence> result = new ArrayList<>();
        for (Chunk chunk : indexed) {
            double score = chunk.hits() / (double) termCount;
            if (mode == QueryMode.CHAT
                    && (chunk.hits() < MIN_CHAT_HITS || score < MIN_CHAT_COVERAGE)) {
                continue;
            }
            result.add(chunk.toEvidence(sourceIds.getOrDefault(chunk.id(), List.of()), score));
        }
        return result;
    }

    private static List<Evidence> scorePending(RawMatch message, List<String> terms) {
        List<Evidence> result = new ArrayList<>();
        var parts = SearchText.chunks(message.body());
        for (int part = 0; part < parts.size(); part++) {
            String body = parts.get(part);
            double score = SearchText.score(body, terms);
            if (score > 0) {
                result.add(
                        new Evidence(
                                "message:" + message.id() + ":" + part,
                                SourceKind.RAW,
                                body,
                                List.of(message.id()),
                                message.at(),
                                score));
            }
        }
        return result;
    }

    private record Chunk(UUID id, SourceKind kind, Content content, int hits) {
        static Chunk from(Map<String, Object> row) {
            Content content =
                    new Content(
                            row.get("source_key") + ":" + row.get("part_index"),
                            (String) row.get("body"),
                            ((Timestamp) row.get("source_time")).toInstant());
            return new Chunk(
                    (UUID) row.get("id"),
                    SourceKind.valueOf((String) row.get("source_kind")),
                    content,
                    ((Number) row.get("hits")).intValue());
        }

        Evidence toEvidence(List<UUID> sourceIds, double score) {
            return new Evidence(
                    content.key(), kind, content.body(), sourceIds, content.at(), score);
        }
    }

    private record Content(String key, String body, Instant at) {}

    private record RawMatch(UUID id, String body, Instant at) {
        static RawMatch from(Map<String, Object> row) {
            return new RawMatch(
                    (UUID) row.get("id"),
                    (String) row.get("body"),
                    ((Timestamp) row.get("occurred_at")).toInstant());
        }
    }

    private record SourceLink(UUID chunkId, UUID messageId) {
        static SourceLink read(java.sql.ResultSet row) throws java.sql.SQLException {
            return new SourceLink(
                    UUID.fromString(row.getString(1)), UUID.fromString(row.getString(2)));
        }
    }
}
