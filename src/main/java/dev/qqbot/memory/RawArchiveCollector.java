/**
 * [INPUT]: 依赖 SQLite 回收队列、群锁及 RAW 重写与文件存储。
 * [OUTPUT]: 执行受约束的回收清单，记录完成、失败与重试时间。
 * [POS]: 已提交回收任务的入口。持有群锁直到文件处理完毕。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static dev.qqbot.memory.RawArchiveGcManifest.REWRITE_EXPIRED;

import dev.qqbot.memory.RawArchiveGcManifest.DeletePaths;
import dev.qqbot.memory.RawArchiveGcManifest.Deletion;
import dev.qqbot.memory.RawArchiveGcManifest.HashedFile;
import dev.qqbot.memory.RawArchiveGcManifest.Rewrite;
import dev.qqbot.memory.RawArchiveGcManifest.Tombstone;
import dev.qqbot.persistence.Db;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@DependsOn("rawArchiveLifecycle")
public class RawArchiveCollector {
    private static final Logger LOG = LoggerFactory.getLogger(RawArchiveCollector.class);
    private static final int POLL_LIMIT = 100;
    private static final long INITIAL_RETRY_MS = 1_000;
    private static final int MAX_BACKOFF_SHIFT = 6;
    private static final long MAX_RETRY_MS = 60_000;
    private static final Duration COMPLETED_RETENTION = Duration.ofDays(7);
    private static final String RECLAIM_FAILED = "RAW_GC_FAILED";
    private final Db db;
    private final RawSegmentStore store;
    private final RawArchiveCompaction compaction;
    private final Clock clock;

    /** 连接同一实例的归档存储和回收队列。 */
    public RawArchiveCollector(
            Db db, RawSegmentStore store, RawArchiveService archive, Clock clock) {
        this.db = db;
        this.store = store;
        this.compaction = new RawArchiveCompaction(db, store, archive, clock);
        this.clock = clock;
    }

    /** 将删除清单加入调用方的 SQLite 事务。群 ID 和 epoch 标识原归档分区。 */
    public static void enqueue(
            Db db, UUID group, long epoch, String operation, Map<String, ?> manifest, long now) {
        // SIDE EFFECT: 此处仅写任务，调用方提交事务后才允许删除文件。
        db.exec(
                """
                insert into raw_archive_gc(id,group_id,epoch,operation,manifest_json,created_ms,updated_ms)
                values(?,?,?,?,?,?,?)
                """,
                UUID.randomUUID(),
                group,
                epoch,
                operation,
                db.json(manifest),
                now,
                now);
    }

    /** 为分段代际和到期水位登记可重复提交的重写任务。 */
    public static void enqueueRewrite(Db db, RawSegment segment, long cutoff, long now) {
        var manifest = new Rewrite(segment.id(), segment.generation(), cutoff);
        UUID task = rewriteIdentity(manifest);
        // SIDE EFFECT: 与调用方的留存更新一并提交，仅在任务首次登记时写入。
        db.exec(
                """
                insert into raw_archive_gc(id,group_id,epoch,operation,manifest_json,created_ms,updated_ms)
                values(?,?,?,?,?,?,?) on conflict do nothing
                """,
                task,
                segment.partition().groupId(),
                segment.partition().epoch(),
                REWRITE_EXPIRED,
                db.json(manifest),
                now,
                now);
    }

    private static UUID rewriteIdentity(Rewrite manifest) {
        // 身份哈希覆盖分段 ID、代际和到期水位。同一重写任务须跨重试去重。
        String identity =
                manifest.segmentId() + ":" + manifest.generation() + ":" + manifest.cutoff();
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    /** 在锁内执行到期任务。删除失败时记录退避，下次轮询重试。 */
    @Scheduled(fixedDelayString = "${bot.memory.raw-archive.gc-poll-ms:60000}")
    public synchronized void runOnce() {
        var tasks =
                db.query(
                        """
                select id,group_id,attempts,operation,manifest_json from raw_archive_gc
                where state<>'DONE' and (next_retry_ms is null or next_retry_ms<=?)
                order by created_ms limit ?
                """,
                        RawArchiveCollector::readTask,
                        clock.millis(),
                        POLL_LIMIT);
        for (Task task : tasks) {
            store.locked(task.group(), () -> collectTask(task));
        }
        pruneCompleted();
    }

    private static Task readTask(ResultSet row, int rowNumber) throws SQLException {
        var stored = new StoredManifest(row.getString("operation"), row.getString("manifest_json"));
        return new Task(
                UUID.fromString(row.getString("id")),
                UUID.fromString(row.getString("group_id")),
                row.getLong("attempts"),
                stored);
    }

    private Void collectTask(Task task) {
        try {
            RawArchiveGcManifest manifest = decodeTask(task);
            // SIDE EFFECT: 已验证的清单开始执行，完成后再更新任务状态。
            markRunning(task.id());
            applyManifest(task.group(), manifest);
            markDone(task.id());
        } catch (RuntimeException failure) {
            recordFailure(task, failure);
        }
        return null;
    }

    private RawArchiveGcManifest decodeTask(Task task) {
        Map<?, ?> fields = db.parse(task.manifest().json(), Map.class);
        return RawArchiveGcManifest.decodeManifest(
                task.group(), task.manifest().operation(), fields);
    }

    private void applyManifest(UUID group, RawArchiveGcManifest manifest) {
        if (manifest instanceof Rewrite rewrite) {
            compaction.rewrite(group, rewrite);
        } else if (manifest instanceof DeletePaths paths) {
            for (Deletion deletion : paths.entries()) {
                deleteFile(deletion);
            }
        }
    }

    private void deleteFile(Deletion deletion) {
        if (deletion instanceof Tombstone entry) {
            db.tx(() -> deleteTombstoned(entry));
        } else if (deletion instanceof HashedFile file) {
            store.deleteExpected(file.path(), file.sha256());
        } else {
            store.delete(deletion.path());
        }
    }

    private Void deleteTombstoned(Tombstone entry) {
        boolean isMatching =
                db.find(
                                "select token from memory_file_tombstone where relative_path=? and token=?",
                                (row, number) -> true,
                                entry.path(),
                                entry.token())
                        .isPresent();
        if (isMatching) {
            // SIDE EFFECT: token 仍匹配才删除，避免旧作业删掉同名新文件。
            store.delete(entry.path());
            db.exec(
                    "delete from memory_file_tombstone where relative_path=? and token=?",
                    entry.path(),
                    entry.token());
        }
        return null;
    }

    private void markRunning(UUID id) {
        db.exec(
                "update raw_archive_gc set state='RUNNING',updated_ms=? where id=?",
                clock.millis(),
                id);
    }

    private void markDone(UUID id) {
        db.exec(
                """
                update raw_archive_gc set state='DONE',error_code=null,next_retry_ms=null,updated_ms=? where id=?
                """,
                clock.millis(),
                id);
    }

    private void recordFailure(Task task, RuntimeException failure) {
        String code =
                failure instanceof RawArchiveException ? failure.getMessage() : RECLAIM_FAILED;
        long previousAttempts = task.attempts();
        db.exec(
                """
                update raw_archive_gc set state='FAILED',error_code=?,attempts=?,next_retry_ms=?,updated_ms=? where id=?
                """,
                code,
                previousAttempts + 1,
                clock.millis() + retryDelayMs(previousAttempts),
                clock.millis(),
                task.id());
        LOG.warn("{} exception={}", RECLAIM_FAILED, failure.getClass().getSimpleName());
    }

    static long retryDelayMs(long previousAttempts) {
        return Math.min(
                MAX_RETRY_MS, INITIAL_RETRY_MS << Math.min(MAX_BACKOFF_SHIFT, previousAttempts));
    }

    private void pruneCompleted() {
        db.exec(
                "delete from raw_archive_gc where state='DONE' and updated_ms<?",
                clock.millis() - COMPLETED_RETENTION.toMillis());
    }

    private record Task(UUID id, UUID group, long attempts, StoredManifest manifest) {}

    private record StoredManifest(String operation, String json) {}
}
