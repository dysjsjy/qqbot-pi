/**
 * [INPUT]: 接收归档分区、冻结水位、旧文件摘要与迁移预算。
 * [OUTPUT]: 计算旧快照指纹，并分批强制写入未发布分段。
 * [POS]: RAW 迁移的文件边界；发布和回收另有事务。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.RawArchiveProperties;
import dev.qqbot.persistence.Db;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.jdbc.core.RowCallbackHandler;

final class RawArchiveMigrationStorage {
    private static final int FRAME_METADATA_BYTES = 16;
    private static final int HASH_BUFFER_BYTES = 65_536;
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String SOURCE_CHANGED = "RAW_MIGRATION_SOURCE_CHANGED";
    private static final List<String> HASHED_MESSAGE_FIELDS =
            List.of(
                    "id",
                    "event_seq",
                    "kind",
                    "body",
                    "sender_id",
                    "sender_name",
                    "occurred_at",
                    "received_at",
                    "reply_to_key",
                    "message_key");

    private final Db db;
    private final RawSegmentStore store;
    private final RawArchiveService archive;
    private final Limits limits;

    RawArchiveMigrationStorage(
            Db db, RawSegmentStore store, RawArchiveService archive, Limits limits) {
        this.db = db;
        this.store = store;
        this.archive = archive;
        this.limits = limits;
    }

    List<RawSegment> buildSegments(RawPartition partition, long high, Consumer<RawSegment> stage) {
        var writer = new SegmentWriter(partition, stage);
        long after = 0;
        for (; ; ) {
            List<RawRecord> page = readSourcePage(partition, high, after);
            if (page.isEmpty()) {
                return writer.finish();
            }
            // SIDE EFFECT: 先完成整页解码，再写入仅本次尝试可见的分段。
            for (RawRecord record : page) {
                writer.append(record);
            }
            writer.flushPending();
            after = page.get(page.size() - 1).seq();
        }
    }

    private List<RawRecord> readSourcePage(RawPartition partition, long high, long after) {
        Clock clock = limits.clock();
        return db.query(
                """
                select * from group_message where group_id=? and epoch=? and kind='TEXT'
                and event_seq>? and event_seq<=? and occurred_at>=? and occurred_at<=?
                order by event_seq limit ?
                """,
                RawRecord::fromSqliteRow,
                partition.groupId(),
                partition.epoch(),
                after,
                high,
                archive.archiveCutoff(clock.instant()),
                clock.instant(),
                limits.properties().batchMessages());
    }

    String fingerprint(RawPartition partition, long high, List<LegacyFile> oldFiles) {
        // 指纹覆盖分区、水位、旧文件路径与内容、按序排列的源消息，用于识别可继续的旧暂存。
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            updateDigest(digest, partition + ":" + high);
            for (LegacyFile file : oldFiles) {
                updateDigest(digest, file.path() + ":" + file.sha256() + "\n");
            }
            hashSourceMessages(digest, partition, high);
            requireUnchangedFiles(oldFiles);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("RAW migration requires " + HASH_ALGORITHM, failure);
        }
    }

    private void hashSourceMessages(MessageDigest digest, RawPartition partition, long high) {
        RowCallbackHandler hashRow = row -> hashMessage(digest, row);
        db.jdbc.query(
                """
                select * from group_message where group_id=? and epoch=? and event_seq<=? order by event_seq
                """,
                hashRow,
                partition.groupId().toString(),
                partition.epoch(),
                high);
    }

    private static void hashMessage(MessageDigest digest, ResultSet row) throws SQLException {
        for (String field : HASHED_MESSAGE_FIELDS) {
            String value = row.getString(field);
            updateDigest(digest, value == null ? "-1:" : value.length() + ":" + value);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private void requireUnchangedFiles(List<LegacyFile> files) {
        for (LegacyFile file : files) {
            if (!file.sha256().equals(hashFile(store.resolve(file.path())))) {
                throw new RawArchiveException(SOURCE_CHANGED);
            }
        }
    }

    static String hashFile(Path path) {
        // 只覆盖文件字节；删除旧文件前用于确认内容没有被替换。
        try (var input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] buffer = new byte[HASH_BUFFER_BYTES];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new RawArchiveException("RAW_MIGRATION_FILE_MISSING", failure);
        }
    }

    record Limits(RawArchiveProperties properties, Clock clock) {}

    record LegacyFile(String path, String sha256) {}

    record Manifest(String highSeq, List<LegacyFile> oldFiles, List<String> stagedPaths) {}

    private final class SegmentWriter {
        private final RawPartition partition;
        private final Consumer<RawSegment> stage;
        private final List<RawSegment> finished = new ArrayList<>();
        private final List<RawRecord> pending = new ArrayList<>();
        private RawSegment active;
        private long pendingBytes;

        SegmentWriter(RawPartition partition, Consumer<RawSegment> stage) {
            this.partition = partition;
            this.stage = stage;
        }

        void append(RawRecord record) {
            int bytes = store.payload(record).length + FRAME_METADATA_BYTES;
            if (exceedsSegmentLimit(bytes)) {
                sealActive();
            }
            if (active == null) {
                active = store.empty(partition, record.seq(), limits.clock().millis());
                stage.accept(active);
            }
            if (!pending.isEmpty() && pendingBytes + bytes > limits.properties().batchBytes()) {
                flushPending();
            }
            pending.add(record);
            pendingBytes += bytes;
        }

        private boolean exceedsSegmentLimit(int bytes) {
            return active != null
                    && active.committedBytes() + pendingBytes + bytes
                            > limits.properties().segmentBytes();
        }

        void flushPending() {
            if (!pending.isEmpty()) {
                active = store.append(active, pending);
                pending.clear();
                pendingBytes = 0;
            }
        }

        private void sealActive() {
            flushPending();
            finished.add(active.sealed());
            active = null;
        }

        List<RawSegment> finish() {
            if (active != null) {
                sealActive();
            }
            return List.copyOf(finished);
        }
    }
}
