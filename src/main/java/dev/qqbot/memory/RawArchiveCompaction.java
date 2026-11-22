/**
 * [INPUT]: 依赖群锁内的重写任务、SQLite 原文来源与已提交分段窗口。
 * [OUTPUT]: 流式写入剩余帧，复核来源后提交新代际并登记旧文件回收。
 * [POS]: RAW 冷留存的重写阶段。调用方锁住整群直到发布结束。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static dev.qqbot.memory.RawArchiveGcManifest.DELETE_FILES;
import static dev.qqbot.memory.RawArchiveGcManifest.PATHS;

import dev.qqbot.memory.RawArchiveGcManifest.Rewrite;
import dev.qqbot.persistence.Db;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowCallbackHandler;

final class RawArchiveCompaction {
    private static final int BATCH_BYTES = 1_048_576;
    private static final String SOURCE_CHANGED = "RAW_SOURCE_CHANGED";
    private static final String RETAINED_SOURCE =
            """
            kind='TEXT' and occurred_at>=? and exists(
              select 1 from memory_document_source s where s.message_id=group_message.id and s.kind='RAW')
            """;
    private final Db db;
    private final RawSegmentStore store;
    private final RawArchiveService archive;
    private final Clock clock;

    RawArchiveCompaction(Db db, RawSegmentStore store, RawArchiveService archive, Clock clock) {
        this.db = db;
        this.store = store;
        this.archive = archive;
        this.clock = clock;
    }

    void rewrite(UUID group, Rewrite task) {
        findSegment(group, task).ifPresent(segment -> rewriteSegment(segment, task.cutoff()));
    }

    private Optional<RawSegment> findSegment(UUID group, Rewrite task) {
        return db.find(
                "select * from raw_archive_segment where id=? and group_id=? and generation=?",
                RawSegment::fromSqliteRow,
                task.segmentId(),
                group,
                task.generation());
    }

    private void rewriteSegment(RawSegment segment, long cutoff) {
        var source = new SourceSnapshot(segment, cutoff, fingerprint(segment, cutoff));
        List<String> oldPaths =
                List.of(
                        store.relative(store.path(segment, "log")),
                        store.relative(store.path(segment, "index")));
        RawSegment replacement = stageReplacement(source);
        db.tx(() -> publishReplacement(source, replacement, oldPaths));
    }

    private RawSegment stageReplacement(SourceSnapshot source) {
        var writer = new ReplacementWriter(nextGeneration(source.segment()));
        store.streamVerified(source.segment(), record -> copySurvivor(source, writer, record));
        RawSegment replacement = writer.finish();
        if (!source.fingerprint().equals(writer.fingerprint())) {
            throw new RawArchiveException(SOURCE_CHANGED);
        }
        return replacement;
    }

    private RawSegment nextGeneration(RawSegment previous) {
        return new RawSegment(
                previous.id(),
                previous.partition(),
                previous.baseSeq(),
                previous.baseSeq(),
                previous.generation() + 1,
                RawSegmentStore.HEADER_BYTES,
                0,
                clock.millis(),
                Long.MAX_VALUE,
                Long.MIN_VALUE,
                "SEALED");
    }

    private void copySurvivor(SourceSnapshot source, ReplacementWriter writer, RawRecord record) {
        if (record.occurredAtMs() < source.cutoff()) {
            return;
        }
        var retained = findRetained(source, record.messageId());
        retained.ifPresent(current -> writer.copyVerified(record, current));
    }

    private Optional<RawRecord> findRetained(SourceSnapshot source, UUID message) {
        RawPartition partition = source.segment().partition();
        return db.find(
                "select * from group_message where id=? and group_id=? and epoch=? and "
                        + RETAINED_SOURCE,
                RawRecord::fromSqliteRow,
                message,
                partition.groupId(),
                partition.epoch(),
                source.cutoff());
    }

    private String fingerprint(RawSegment segment, long cutoff) {
        MessageDigest digest = sha256();
        db.jdbc.query(
                """
                select id,event_seq from group_message where group_id=? and epoch=?
                  and event_seq>=? and event_seq<=? and %s order by event_seq
                """
                        .formatted(RETAINED_SOURCE),
                (RowCallbackHandler) row -> includeSource(digest, row.getString(1), row.getLong(2)),
                segment.partition().groupId().toString(),
                segment.partition().epoch(),
                segment.baseSeq(),
                segment.lastSeq(),
                cutoff);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void includeSource(MessageDigest digest, String id, long seq) {
        // 指纹只覆盖按序来源 ID 与序号。正文一致性在复制时逐帧核对。
        digest.update((id + ":" + seq + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Void publishReplacement(
            SourceSnapshot source, RawSegment replacement, List<String> oldPaths) {
        verifyStillCurrent(source);
        // SIDE EFFECT: 分段切换与旧文件回收清单在同一 SQLite 事务提交。
        if (replacement.recordCount() == 0) {
            db.exec("delete from raw_archive_segment where id=?", source.segment().id());
        } else {
            archive.publishSegment(replacement);
        }
        RawPartition partition = source.segment().partition();
        RawArchiveCollector.enqueue(
                db,
                partition.groupId(),
                partition.epoch(),
                DELETE_FILES,
                Map.of(PATHS, oldPaths),
                clock.millis());
        return null;
    }

    private void verifyStillCurrent(SourceSnapshot source) {
        RawSegment old = source.segment();
        boolean matches =
                db.find(
                                "select id from raw_archive_segment where id=? and generation=? and committed_bytes=?",
                                (row, number) -> true,
                                old.id(),
                                old.generation(),
                                old.committedBytes())
                        .isPresent();
        if (!matches || !source.fingerprint().equals(fingerprint(old, source.cutoff()))) {
            throw new RawArchiveException(SOURCE_CHANGED);
        }
    }

    private record SourceSnapshot(RawSegment segment, long cutoff, String fingerprint) {}

    private final class ReplacementWriter {
        private final List<RawRecord> pending = new ArrayList<>();
        private final MessageDigest written = sha256();
        private RawSegment replacement;
        private int bytes;

        ReplacementWriter(RawSegment replacement) {
            this.replacement = replacement;
        }

        void copyVerified(RawRecord record, RawRecord current) {
            if (!record.equals(current)) {
                throw new RawArchiveException(SOURCE_CHANGED);
            }
            includeSource(written, record.messageId().toString(), record.seq());
            int frameBytes = store.frameBytes(record);
            if (!pending.isEmpty() && bytes + frameBytes > BATCH_BYTES) {
                flush();
            }
            pending.add(record);
            bytes += frameBytes;
        }

        RawSegment finish() {
            flush();
            return replacement;
        }

        String fingerprint() {
            return HexFormat.of().formatHex(written.digest());
        }

        private void flush() {
            if (pending.isEmpty()) {
                return;
            }
            // SIDE EFFECT: 新帧落盘，此处不移动旧代际的提交边界。
            replacement = store.append(replacement, pending);
            pending.clear();
            bytes = 0;
        }
    }
}
