/**
 * [INPUT]: 接收已扫描原文、已持久化的分段及发布检查点。
 * [OUTPUT]: 复核会话与来源，在同一 SQLite 事务中更新水位、分段和文档引用。
 * [POS]: 在线追加的事务边界；离线迁移与回收复用分段重叠检查。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.memory.RawArchiveService.Batch;
import dev.qqbot.persistence.Db;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

final class RawArchivePublication {
    private static final String SEGMENT_OVERLAP = "RAW_SEGMENT_OVERLAP";
    private static final String WATERMARK_CHANGED = "RAW_WATERMARK_CHANGED";
    private final Db db;
    private final Clock clock;

    RawArchivePublication(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    void publish(Batch batch, List<RawSegment> segments, Consumer<String> checkpoint) {
        checkpoint.accept("beforeCommit");
        db.tx(() -> publishInTransaction(batch, segments, checkpoint));
        checkpoint.accept("afterCommit");
    }

    private Void publishInTransaction(
            Batch batch, List<RawSegment> segments, Consumer<String> checkpoint) {
        requireCurrentScope(batch.partition());
        requireUnchangedWatermark(batch);
        requireDisjointSegments(segments);
        List<DocumentLinks> links = prepareLinks(batch, segments);
        // SIDE EFFECT: 水位、分段和引用依赖此事务一起提交或回滚。
        advanceWatermark(batch);
        for (RawSegment segment : segments) {
            upsertSegment(segment);
        }
        for (DocumentLinks document : links) {
            publishLinks(document);
        }
        checkpoint.accept("inCommit");
        return null;
    }

    private void requireCurrentScope(RawPartition partition) {
        RawArchiveState.Group current = RawArchiveState.requireGroup(db, partition.groupId());
        if (!current.isEnabled() || current.partition().epoch() != partition.epoch()) {
            throw new RawArchiveException("RAW_SCOPE_CHANGED");
        }
    }

    private void requireUnchangedWatermark(Batch batch) {
        boolean isCurrent =
                db.find(
                                """
                                select 1 from raw_archive_state where group_id=? and epoch=? and last_scanned_seq=?
                                """,
                                (row, rowNumber) -> true,
                                batch.partition().groupId(),
                                batch.partition().epoch(),
                                batch.previous())
                        .isPresent();
        if (!isCurrent) {
            throw new RawArchiveException(WATERMARK_CHANGED);
        }
    }

    private void requireDisjointSegments(List<RawSegment> segments) {
        RawSegment previous = null;
        for (RawSegment segment : segments) {
            requireNoOverlap(segment);
            if (previous != null && previous.lastSeq() >= segment.baseSeq()) {
                throw new RawArchiveException(SEGMENT_OVERLAP);
            }
            previous = segment;
        }
    }

    private List<DocumentLinks> prepareLinks(Batch batch, List<RawSegment> segments) {
        var bySegment = new LinkedHashMap<RawSegment, List<UUID>>();
        for (RawRecord record : batch.records()) {
            requireUnchangedSource(batch.partition(), record);
            RawSegment segment = segmentFor(segments, record.seq());
            bySegment
                    .computeIfAbsent(segment, ignored -> new ArrayList<>())
                    .add(record.messageId());
        }
        List<DocumentLinks> result = new ArrayList<>();
        for (var entry : bySegment.entrySet()) {
            result.add(prepareDocument(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(result);
    }

    private void requireUnchangedSource(RawPartition partition, RawRecord record) {
        boolean matches =
                db.find(
                                "select * from group_message where id=? and group_id=? and epoch=?",
                                RawRecord::fromSqliteRow,
                                record.messageId(),
                                partition.groupId(),
                                partition.epoch())
                        .filter(record::equals)
                        .isPresent();
        if (!matches) {
            throw new RawArchiveException("RAW_SOURCE_CHANGED");
        }
    }

    private static RawSegment segmentFor(List<RawSegment> segments, long sequence) {
        return segments.stream()
                .filter(segment -> segment.baseSeq() <= sequence && sequence <= segment.lastSeq())
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "RAW segment missing for sequence=" + sequence));
    }

    private DocumentLinks prepareDocument(RawSegment segment, List<UUID> sources) {
        Optional<UUID> existing =
                db.find(
                        """
                        select d.id from memory_document d join raw_archive_ref r on r.document_id=d.id
                        where r.segment_id=? order by d.created_at,d.id limit 1
                        """,
                        (row, rowNumber) -> UUID.fromString(row.getString("id")),
                        segment.id());
        return new DocumentLinks(segment, existing.orElse(null), sources);
    }

    private void advanceWatermark(Batch batch) {
        int updated =
                db.exec(
                        """
                        update raw_archive_state set last_scanned_seq=?,status='ACTIVE',error_code=null,
                        attempts=0,next_retry_ms=null,updated_ms=? where group_id=? and epoch=? and last_scanned_seq=?
                        """,
                        batch.scanned(),
                        clock.millis(),
                        batch.partition().groupId(),
                        batch.partition().epoch(),
                        batch.previous());
        if (updated != 1) {
            throw new RawArchiveException(WATERMARK_CHANGED);
        }
    }

    private void publishLinks(DocumentLinks links) {
        UUID document = links.existingDocument();
        if (document == null) {
            document = createDocument(links.segment());
        }
        for (UUID source : links.sources()) {
            db.exec(
                    "insert into memory_document_source(document_id,message_id,kind) values(?,?,'RAW')",
                    document,
                    source);
            db.exec(
                    "update memory_document set source_count=source_count+1,finished_at=? where id=?",
                    clock.instant(),
                    document);
        }
    }

    private UUID createDocument(RawSegment segment) {
        UUID id = UUID.randomUUID();
        db.exec(
                """
                insert into memory_document(id,group_id,epoch,kind,state,source_count,storage,created_at,finished_at)
                values(?,?,?,'RAW','READY',0,'SEGMENT_V1',?,?)
                """,
                id,
                segment.partition().groupId(),
                segment.partition().epoch(),
                clock.instant(),
                clock.instant());
        db.exec(
                "insert into raw_archive_ref(document_id,segment_id) values(?,?)",
                id,
                segment.id());
        return id;
    }

    void publishSegment(RawSegment segment) {
        requireNoOverlap(segment);
        // SIDE EFFECT: 迁移和回收在各自的引用更新事务内调用。
        upsertSegment(segment);
    }

    private void requireNoOverlap(RawSegment segment) {
        boolean hasOverlap =
                db.find(
                                """
                                select id from raw_archive_segment where group_id=? and epoch=? and id<>?
                                and base_seq<=? and last_seq>=? limit 1
                                """,
                                (row, rowNumber) -> true,
                                segment.partition().groupId(),
                                segment.partition().epoch(),
                                segment.id(),
                                segment.lastSeq(),
                                segment.baseSeq())
                        .isPresent();
        if (hasOverlap) {
            throw new RawArchiveException(SEGMENT_OVERLAP);
        }
    }

    private void upsertSegment(RawSegment segment) {
        db.exec(
                """
                insert into raw_archive_segment(id,group_id,epoch,base_seq,last_seq,generation,committed_bytes,
                    record_count,opened_ms,min_occurred_ms,max_occurred_ms,state) values(?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(id) do update set last_seq=excluded.last_seq,generation=excluded.generation,
                    committed_bytes=excluded.committed_bytes,record_count=excluded.record_count,
                    min_occurred_ms=excluded.min_occurred_ms,max_occurred_ms=excluded.max_occurred_ms,
                    state=excluded.state
                """,
                segment.id(),
                segment.partition().groupId(),
                segment.partition().epoch(),
                segment.baseSeq(),
                segment.lastSeq(),
                segment.generation(),
                segment.committedBytes(),
                segment.recordCount(),
                segment.openedMs(),
                segment.minOccurredMs(),
                segment.maxOccurredMs(),
                segment.state());
    }

    private record DocumentLinks(RawSegment segment, UUID existingDocument, List<UUID> sources) {
        DocumentLinks {
            sources = List.copyOf(sources);
        }
    }
}
