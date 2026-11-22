/**
 * [INPUT]: 依赖归档状态、分段存储与冷留存配置。
 * [OUTPUT]: 在群锁内编排扫描、写文件与发布；提供归档状态、重试和分页读取。
 * [POS]: 在线 RAW 归档入口；适配状态响应，不解释分段内容。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.qqbot.RawArchiveProperties;
import dev.qqbot.RetentionProperties;
import dev.qqbot.memory.RawArchiveState.Counts;
import dev.qqbot.memory.RawArchiveState.Group;
import dev.qqbot.memory.RawArchiveState.Snapshot;
import dev.qqbot.persistence.Db;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToIntFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RawArchiveService {
    private final RawSegmentStore store;
    private final RawArchiveProperties properties;
    private final Clock clock;
    private final RetentionProperties retention;
    private final RawArchivePageReader reader;
    private final Db db;
    private final RawArchivePublication publication;
    private final RawArchiveState state;

    /** 创建使用默认冷留存的归档服务。 */
    public RawArchiveService(
            Db db, RawSegmentStore store, RawArchiveProperties properties, Clock clock) {
        this(db, store, properties, clock, RetentionProperties.defaults());
    }

    /** 创建使用部署冷留存的归档服务。 */
    @Autowired
    public RawArchiveService(
            Db db,
            RawSegmentStore store,
            RawArchiveProperties properties,
            Clock clock,
            RetentionProperties retention) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        this.retention = retention;
        this.reader = new RawArchivePageReader(db, store, clock, retention);
        this.db = db;
        this.publication = new RawArchivePublication(db, clock);
        this.state = new RawArchiveState(db, properties, clock);
    }

    Instant archiveCutoff(Instant now) {
        return retention.archiveCutoff(now);
    }

    long diskBytes(List<RawSegment> segments) {
        long bytes = 0;
        for (RawSegment segment : segments) {
            bytes += fileBytes(segment, "log");
            bytes += fileBytes(segment, "index");
        }
        return bytes;
    }

    private long fileBytes(RawSegment segment, String extension) {
        try {
            return Files.size(store.path(segment, extension));
        } catch (NoSuchFileException missing) {
            return 0;
        } catch (IOException failure) {
            throw new RawArchiveException("RAW_DISK_USAGE_UNAVAILABLE", failure);
        }
    }

    void initialize(UUID groupId) {
        state.initialize(groupId);
    }

    /** 归档一批原文；分段文件先落盘，发布失败时保留原提交水位。 */
    public int archiveBatch(UUID groupId) {
        Objects.requireNonNull(groupId, "groupId must not be null");
        if (!properties.enabled()) {
            return 0;
        }
        return store.locked(groupId, () -> archiveLocked(groupId));
    }

    private int archiveLocked(UUID groupId) {
        Snapshot snapshot = state.initialize(groupId);
        Group group = state.requireGroup(groupId);
        if (!state.canAppend(group, snapshot)) {
            return 0;
        }
        try {
            return append(window(group.partition(), snapshot.lastScannedSeq()));
        } catch (RuntimeException failure) {
            state.recordFailure(group.partition(), snapshot.attempts() + 1, failure);
            throw failure;
        }
    }

    private Window window(RawPartition partition, long previous) {
        Instant now = clock.instant();
        return new Window(partition, previous, now, archiveCutoff(now));
    }

    private int append(Window window) {
        Batch batch = scan(window);
        if (!batch.advancesWatermark()) {
            return 0;
        }
        // SIDE EFFECT: 先持久化文件，再在 SQLite 事务中复核并发布引用。
        List<RawSegment> segments = writeSegments(window.partition(), batch.records());
        publication.publish(batch, segments, this::checkpoint);
        return batch.records().size();
    }

    protected void checkpoint(String phase) {}

    void publishSegment(RawSegment segment) {
        publication.publishSegment(segment);
    }

    /** 返回当前群的归档状态和留存窗口计数；未知群返回 404。 */
    public Status status(UUID groupId) {
        Snapshot snapshot = state.initialize(groupId);
        Group group = state.requireGroup(groupId);
        Counts counts = state.countStatus(window(group.partition(), snapshot.lastScannedSeq()));
        return new Status(
                "SEGMENT_V1",
                properties.enabled() ? snapshot.status() : RawArchiveState.PAUSED,
                Long.toString(snapshot.lastScannedSeq()),
                counts.pending(),
                counts.archived(),
                counts.segments(),
                counts.pendingCleanup(),
                counts.failedCleanup(),
                snapshot.expired(),
                snapshot.errorCode());
    }

    /** 清除当前群的退避；归档需迁移或含损坏分段时返回 409。 */
    public Status retry(UUID groupId) {
        state.retry(state.initialize(groupId));
        return status(groupId);
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Status(
            String format,
            String status,
            String lastScannedSeq,
            long pendingMessages,
            long archivedMessages,
            long segments,
            long gcPending,
            long gcFailed,
            long expiredBeforeArchive,
            String errorCode) {}

    /** 按群当前代际读取 RAW 页；游标过期或分段损坏时返回对应错误。 */
    public Map<String, Object> readDocument(
            UUID groupId, UUID documentId, String cursor, int limit) {
        return reader.readDocument(groupId, documentId, cursor, limit);
    }

    /** 将尚未迁移到行映射器的 SQLite 整数字段转为长整数。 */
    public static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static final String FIND_ROWS =
            """
            select m.*, exists(
                select 1 from memory_document_source s where s.message_id=m.id and s.kind='RAW'
            ) as already_archived
            from group_message m where m.group_id=? and m.epoch=? and m.event_seq>?
            order by m.event_seq limit ?
            """;

    private Batch scan(Window window) {
        requireSequenceNumbers(window.partition());
        List<Candidate> candidates = readCandidates(window);
        return planBatch(window, candidates, properties.batchBytes(), store::frameBytes);
    }

    private void requireSequenceNumbers(RawPartition partition) {
        boolean hasMissingSequence =
                db.find(
                                """
                                select 1 from group_message where group_id=? and epoch=? and event_seq is null limit 1
                                """,
                                (row, rowNumber) -> true,
                                partition.groupId(),
                                partition.epoch())
                        .isPresent();
        if (hasMissingSequence) {
            throw new RawArchiveException("RAW_SEQUENCE_MISSING");
        }
    }

    private List<Candidate> readCandidates(Window window) {
        return db.query(
                FIND_ROWS,
                (row, rowNumber) -> readCandidate(window, row, rowNumber),
                window.partition().groupId(),
                window.partition().epoch(),
                window.previous(),
                properties.batchMessages());
    }

    private static Candidate readCandidate(Window window, ResultSet row, int rowNumber)
            throws SQLException {
        long sequence = row.getLong("event_seq");
        if (!RawRecord.TEXT.equals(row.getString("kind"))) {
            return new Candidate(sequence, false, null);
        }
        Instant occurred = Instant.ofEpochMilli(row.getLong("occurred_at"));
        if (occurred.isAfter(window.now())) {
            return new Candidate(sequence, true, null);
        }
        if (occurred.isBefore(window.cutoff()) || row.getBoolean("already_archived")) {
            return new Candidate(sequence, false, null);
        }
        return new Candidate(sequence, false, RawRecord.fromSqliteRow(row, rowNumber));
    }

    static Batch planBatch(
            Window window,
            List<Candidate> candidates,
            int byteLimit,
            ToIntFunction<RawRecord> sizeOf) {
        List<RawRecord> selected = new ArrayList<>();
        long scanned = window.previous();
        long bytes = 0;
        for (Candidate candidate : candidates) {
            if (candidate.isFutureText()) {
                break;
            }
            RawRecord record = candidate.record();
            if (record != null) {
                int size = sizeOf.applyAsInt(record);
                // 第一条记录不受批次预算限制，避免大消息永久堵住水位。
                if (!selected.isEmpty() && bytes + size > byteLimit) {
                    break;
                }
                selected.add(record);
                bytes += size;
            }
            scanned = candidate.sequence();
        }
        return new Batch(window.partition(), window.previous(), scanned, selected);
    }

    record Window(RawPartition partition, long previous, Instant now, Instant cutoff) {}

    record Candidate(long sequence, boolean isFutureText, RawRecord record) {}

    record Batch(RawPartition partition, long previous, long scanned, List<RawRecord> records) {
        Batch {
            records = List.copyOf(records);
        }

        boolean advancesWatermark() {
            return scanned > previous;
        }
    }

    private List<RawSegment> writeSegments(RawPartition partition, List<RawRecord> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        var session = new WriteSession(partition, findActive(partition).orElse(null));
        // SIDE EFFECT: 文件先落盘；SQLite 发布失败时，已提交边界不变。
        for (RawRecord record : records) {
            session.accept(record);
        }
        return session.finish();
    }

    private Optional<RawSegment> findActive(RawPartition partition) {
        return db.find(
                "select * from raw_archive_segment where group_id=? and epoch=? and state='ACTIVE'",
                RawSegment::fromSqliteRow,
                partition.groupId(),
                partition.epoch());
    }

    private final class WriteSession {
        private final RawPartition partition;
        private final List<RawSegment> changed = new ArrayList<>();
        private final List<RawRecord> pending = new ArrayList<>();
        private RawSegment active;
        private long pendingBytes;

        WriteSession(RawPartition partition, RawSegment active) {
            this.partition = partition;
            this.active = active;
        }

        void accept(RawRecord record) {
            int frameBytes = store.frameBytes(record);
            if (active != null && needsRotation(frameBytes)) {
                sealActive();
            }
            if (active == null) {
                active = store.empty(partition, record.seq(), clock.millis());
            }
            pending.add(record);
            pendingBytes += frameBytes;
        }

        private boolean needsRotation(int nextFrameBytes) {
            long total = active.committedBytes() + pendingBytes + nextFrameBytes;
            long ageMs = clock.millis() - active.openedMs();
            return total > properties.segmentBytes()
                    || ageMs >= properties.segmentMaxAgeSeconds() * 1_000;
        }

        private void sealActive() {
            flush();
            changed.add(active.sealed());
            active = null;
        }

        private void flush() {
            if (!pending.isEmpty()) {
                active = store.append(active, pending);
                pending.clear();
                pendingBytes = 0;
            }
        }

        List<RawSegment> finish() {
            if (!pending.isEmpty()) {
                flush();
                changed.add(active);
            }
            return List.copyOf(changed);
        }
    }
}
