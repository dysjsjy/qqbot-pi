/**
 * [INPUT]: 接收 SQLite、归档开关、会话范围与冷留存窗口。
 * [OUTPUT]: 初始化归档状态；查询水位与计数；记录失败和退避。
 * [POS]: 在线 RAW 状态的 SQLite 边界；不写分段或文档引用。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.RawArchiveProperties;
import dev.qqbot.memory.RawArchiveService.Window;
import dev.qqbot.persistence.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class RawArchiveState {
    static final String PAUSED = "PAUSED";
    private static final String ACTIVE = "ACTIVE";
    private static final String NEEDS_MIGRATION = "NEEDS_MIGRATION";
    private static final int FIRST_RETRY_DELAY_MS = 1_000;
    private static final int MAX_RETRY_DELAY_MS = 60_000;
    private static final int MAX_RETRY_EXPONENT = 6;
    private static final String COUNT_PENDING =
            """
            select count(*) from group_message m where group_id=? and epoch=? and kind='TEXT'
            and occurred_at>=? and occurred_at<=? and not exists(
                select 1 from memory_document_source s where s.message_id=m.id and s.kind='RAW'
            )
            """;
    private static final String COUNT_ARCHIVED =
            """
            select count(*) from memory_document_source s join group_message m on m.id=s.message_id
            where m.group_id=? and m.epoch=? and s.kind='RAW' and m.occurred_at>=? and m.occurred_at<=?
            """;

    private final Db db;
    private final RawArchiveProperties properties;
    private final Clock clock;

    RawArchiveState(Db db, RawArchiveProperties properties, Clock clock) {
        this.db = db;
        this.properties = properties;
        this.clock = clock;
    }

    Snapshot initialize(UUID groupId) {
        return db.tx(() -> initializeInTransaction(groupId));
    }

    private Snapshot initializeInTransaction(UUID groupId) {
        RawPartition partition = requireGroup(groupId).partition();
        String configured = properties.enabled() ? ACTIVE : PAUSED;
        String initial = hasLegacyArchive(partition) ? NEEDS_MIGRATION : configured;
        // SIDE EFFECT: 创建缺失状态，仅同步 ACTIVE/PAUSED，不清除迁移和失败标记。
        db.exec(
                """
                insert into raw_archive_state(group_id,epoch,status,updated_ms) values(?,?,?,?)
                on conflict do nothing
                """,
                groupId,
                partition.epoch(),
                initial,
                clock.millis());
        db.exec(
                """
                update raw_archive_state set status=? where group_id=? and epoch=? and status in ('ACTIVE','PAUSED')
                """,
                configured,
                groupId,
                partition.epoch());
        return requireSnapshot(partition);
    }

    private boolean hasLegacyArchive(RawPartition partition) {
        return exists(
                """
                select 1 from memory_document where group_id=? and epoch=? and kind='RAW' and storage='MARKDOWN'
                limit 1
                """,
                partition.groupId(),
                partition.epoch());
    }

    Group requireGroup(UUID groupId) {
        return requireGroup(db, groupId);
    }

    static Group requireGroup(Db db, UUID groupId) {
        return db.find(
                        "select epoch,enabled from group_config where id=?",
                        (row, rowNumber) -> readGroup(groupId, row),
                        groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static Group readGroup(UUID id, ResultSet row) throws SQLException {
        return new Group(new RawPartition(id, row.getLong("epoch")), row.getBoolean("enabled"));
    }

    private Snapshot requireSnapshot(RawPartition partition) {
        return db.find(
                        "select * from raw_archive_state where group_id=? and epoch=?",
                        (row, rowNumber) -> readState(partition, row),
                        partition.groupId(),
                        partition.epoch())
                .orElseThrow(() -> new IllegalStateException("Missing RAW state for " + partition));
    }

    private static Snapshot readState(RawPartition partition, ResultSet row) throws SQLException {
        long retry = row.getLong("next_retry_ms");
        Long nextRetryMs = row.wasNull() ? null : retry;
        return new Snapshot(
                partition,
                row.getString("status"),
                row.getLong("last_scanned_seq"),
                row.getLong("expired_before_archive"),
                row.getString("error_code"),
                row.getLong("attempts"),
                nextRetryMs);
    }

    boolean canAppend(Group group, Snapshot snapshot) {
        RawPartition partition = group.partition();
        return group.isEnabled()
                && !snapshot.needsMigration()
                && !snapshot.isWaiting(clock.millis())
                && !exists(
                        "select 1 from raw_archive_segment where group_id=? and epoch=? and state='BROKEN' limit 1",
                        partition.groupId(),
                        partition.epoch());
    }

    void recordFailure(RawPartition partition, long attempts, RuntimeException failure) {
        String code =
                failure instanceof RawArchiveException
                        ? failure.getMessage()
                        : "RAW_ARCHIVE_COMMIT_FAILED";
        long delayMs =
                Math.min(
                        MAX_RETRY_DELAY_MS,
                        (long) FIRST_RETRY_DELAY_MS << Math.min(MAX_RETRY_EXPONENT, attempts - 1));
        // SIDE EFFECT: 在发布事务外记录失败和退避，不修改已提交水位。
        db.exec(
                """
                update raw_archive_state set status='FAILED',error_code=?,attempts=?,next_retry_ms=?,updated_ms=?
                where group_id=? and epoch=?
                """,
                code,
                attempts,
                clock.millis() + delayMs,
                clock.millis(),
                partition.groupId(),
                partition.epoch());
        if (RawSegmentFormat.CORRUPT.equals(code)) {
            markActiveSegmentBroken(partition);
        }
    }

    private void markActiveSegmentBroken(RawPartition partition) {
        db.exec(
                "update raw_archive_segment set state='BROKEN' where group_id=? and epoch=? and state='ACTIVE'",
                partition.groupId(),
                partition.epoch());
    }

    void retry(Snapshot snapshot) {
        RawPartition partition = snapshot.partition();
        if (snapshot.needsMigration()
                || exists(
                        "select 1 from raw_archive_segment where group_id=? and state='BROKEN' limit 1",
                        partition.groupId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先离线迁移或校验归档");
        }
        // SIDE EFFECT: 清除已排除迁移或损坏的分区退避状态。
        db.exec(
                """
                update raw_archive_state set status='ACTIVE',error_code=null,attempts=0,next_retry_ms=null
                where group_id=? and epoch=?
                """,
                partition.groupId(),
                partition.epoch());
    }

    Counts countStatus(Window window) {
        RawPartition partition = window.partition();
        UUID groupId = partition.groupId();
        long pending = countWindow(COUNT_PENDING, window);
        long archived = countWindow(COUNT_ARCHIVED, window);
        long segments =
                count(
                        "select count(*) from raw_archive_segment where group_id=? and epoch=?",
                        groupId,
                        partition.epoch());
        long pendingCleanup =
                count(
                        "select count(*) from raw_archive_gc where group_id=? and state in ('PENDING','RUNNING')",
                        groupId);
        long failedCleanup =
                count(
                        "select count(*) from raw_archive_gc where group_id=? and state='FAILED'",
                        groupId);
        return new Counts(pending, archived, segments, pendingCleanup, failedCleanup);
    }

    private long countWindow(String query, Window window) {
        RawPartition partition = window.partition();
        return count(query, partition.groupId(), partition.epoch(), window.cutoff(), window.now());
    }

    private long count(String query, Object... bindings) {
        return db.find(query, (row, rowNumber) -> row.getLong(1), bindings).orElseThrow();
    }

    private boolean exists(String query, Object... bindings) {
        return db.find(query, (row, rowNumber) -> true, bindings).isPresent();
    }

    record Group(RawPartition partition, boolean isEnabled) {}

    record Snapshot(
            RawPartition partition,
            String status,
            long lastScannedSeq,
            long expired,
            String errorCode,
            long attempts,
            Long nextRetryMs) {
        boolean needsMigration() {
            return NEEDS_MIGRATION.equals(status);
        }

        boolean isWaiting(long nowMs) {
            return nextRetryMs != null && nextRetryMs > nowMs;
        }
    }

    record Counts(
            long pending, long archived, long segments, long pendingCleanup, long failedCleanup) {}
}
