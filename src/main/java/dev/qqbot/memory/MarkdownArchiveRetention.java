/**
 * [INPUT]: 依赖归档来源引用、冷留存截止时间和分段回收队列。
 * [OUTPUT]: 清除整群归档或裁剪过期引用和文档，登记分段重写任务。
 * [POS]: 归档到期写边界。调用方限定群和事务范围。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.persistence.Db;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

final class MarkdownArchiveRetention {
    private final Db db;
    private final MarkdownMemory files;
    private final Clock clock;

    MarkdownArchiveRetention(Db db, MarkdownMemory files, Clock clock) {
        this.db = db;
        this.files = files;
        this.clock = clock;
    }

    void clear(UUID groupId) {
        files.delete(groupId);
        db.exec("delete from memory_document where group_id=?", groupId);
        db.exec("delete from raw_archive_segment where group_id=?", groupId);
        db.exec("delete from raw_archive_state where group_id=?", groupId);
    }

    void expire(UUID groupId, Instant cutoff) {
        countUnarchived(groupId, cutoff);
        enqueueSegmentRewrites(groupId, cutoff);
        trimSegmentReferences(groupId, cutoff);
        deleteStaleDocuments(groupId, cutoff);
    }

    private void countUnarchived(UUID groupId, Instant cutoff) {
        db.exec(
                """
                update raw_archive_state set expired_before_archive=expired_before_archive+(
                  select count(*) from group_message m where m.group_id=raw_archive_state.group_id
                    and m.epoch=raw_archive_state.epoch and m.kind='TEXT' and m.occurred_at<?
                    and not exists(select 1 from memory_document_source s where s.message_id=m.id and s.kind='RAW'))
                where group_id=?
                """,
                cutoff,
                groupId);
    }

    private void enqueueSegmentRewrites(UUID groupId, Instant cutoff) {
        var segments =
                db.list(
                        "select * from raw_archive_segment where group_id=? and min_occurred_ms<?",
                        groupId,
                        cutoff.toEpochMilli());
        for (var row : segments) {
            RawArchiveCollector.enqueueRewrite(
                    db, RawSegment.from(row), cutoff.toEpochMilli(), clock.millis());
        }
    }

    private void trimSegmentReferences(UUID groupId, Instant cutoff) {
        db.exec(
                """
                delete from memory_document_source where kind='RAW'
                  and document_id in (select id from memory_document where group_id=? and storage='SEGMENT_V1')
                  and message_id in (select id from group_message where occurred_at<?)
                """,
                groupId,
                cutoff);
        db.exec(
                """
                update memory_document set source_count=(select count(*) from memory_document_source s
                  where s.document_id=memory_document.id) where group_id=? and storage='SEGMENT_V1'
                """,
                groupId);
        db.exec(
                "delete from memory_document where group_id=? and storage='SEGMENT_V1' and source_count=0",
                groupId);
    }

    private void deleteStaleDocuments(UUID groupId, Instant cutoff) {
        var stale =
                db.query(
                        """
                select d.id,d.kind from memory_document d where d.group_id=? and d.storage='MARKDOWN'
                and (exists(select 1 from memory_document_source s join group_message m on m.id=s.message_id
                  where s.document_id=d.id and (m.occurred_at<? or m.group_id<>d.group_id or m.epoch<>d.epoch))
                  or (d.kind='SUMMARY' and d.source_count<>(select count(*) from memory_document_source s
                    where s.document_id=d.id)))
                """,
                        (row, index) ->
                                new Document(UUID.fromString(row.getString(1)), row.getString(2)),
                        groupId,
                        cutoff);
        for (Document document : stale) {
            files.deleteDocument(groupId, document.id(), document.kind());
            db.exec("delete from memory_document where id=?", document.id());
        }
    }

    private record Document(UUID id, String kind) {}
}
