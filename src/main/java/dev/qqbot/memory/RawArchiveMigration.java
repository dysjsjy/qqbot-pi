/**
 * [INPUT]: 依赖 SQLite 元数据、原文分段、只读校验器与发布事务。
 * [OUTPUT]: 校验并迁移原文分区；将已校验的归档交给 V9 导出器。
 * [POS]: 离线兼容入口。实例锁由命令入口持有。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.RawArchiveProperties;
import dev.qqbot.memory.RawArchiveMigrationPublication.PublishedMigration;
import dev.qqbot.memory.RawArchiveMigrationStorage.LegacyFile;
import dev.qqbot.memory.RawArchiveMigrationStorage.Manifest;
import dev.qqbot.memory.RawArchiveMigrationVerifier.LegacyDocument;
import dev.qqbot.persistence.Db;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;

public class RawArchiveMigration {
    private final Db db;
    private final RawSegmentStore store;
    private final RawArchiveService archive;
    private final Clock clock;
    private final RawArchiveMigrationVerifier verifier;
    private final RawArchiveMigrationStorage storage;
    private final RawArchiveMigrationPublication publication;

    public RawArchiveMigration(
            Db db,
            RawSegmentStore store,
            RawArchiveService archive,
            RawArchiveProperties properties,
            Clock clock) {
        this.db = db;
        this.store = store;
        this.archive = archive;
        this.clock = clock;
        this.verifier = new RawArchiveMigrationVerifier(db, store);
        this.storage =
                new RawArchiveMigrationStorage(
                        db,
                        store,
                        archive,
                        new RawArchiveMigrationStorage.Limits(properties, clock));
        this.publication = new RawArchiveMigrationPublication(db, store, archive, clock);
    }

    /** 汇总旧式归档兼容性和计数；未指定群时检查全库。 */
    public Map<String, Object> inspect(UUID group) {
        return verifier.inspect(group);
    }

    /** 迁移当前群代际；已经发布的迁移只重新校验，不再写分段。 */
    public Map<String, Object> migrate(UUID groupId) {
        Objects.requireNonNull(groupId, "groupId must not be null");
        return store.locked(groupId, () -> migrateLocked(groupId));
    }

    private Map<String, Object> migrateLocked(UUID groupId) {
        RawPartition partition = requirePartition(groupId);
        return publication
                .findPublishedMigration(partition)
                .map(published -> reusePublishedMigration(partition, published))
                .orElseGet(() -> migrateSource(requireMigrationSource(partition)));
    }

    private RawPartition requirePartition(UUID groupId) {
        long epoch =
                db.find(
                                "select epoch from group_config where id=?",
                                (row, rowNumber) -> row.getLong("epoch"),
                                groupId)
                        .orElseThrow(() -> new RawArchiveException("RAW_GROUP_MISSING"));
        return new RawPartition(groupId, epoch);
    }

    private Map<String, Object> reusePublishedMigration(
            RawPartition partition, PublishedMigration published) {
        verify(partition.groupId());
        if (published.isFinalized()) {
            return Map.of("state", "FINALIZED", "reused", true);
        }
        publication.markVerified(published.id());
        return Map.of("state", "VERIFIED", "reused", true);
    }

    private SourceSnapshot requireMigrationSource(RawPartition partition) {
        requireUnsegmentedSources(partition);
        long high = highSeq(partition);
        List<LegacyFile> files = validateLegacyFiles(partition);
        return new SourceSnapshot(
                partition, high, files, storage.fingerprint(partition, high, files));
    }

    private void requireUnsegmentedSources(RawPartition partition) {
        UUID group = partition.groupId();
        long epoch = partition.epoch();
        if (exists(
                "select 1 from raw_archive_segment where group_id=? and epoch=? limit 1",
                group,
                epoch)) {
            throw new RawArchiveException("RAW_MIGRATION_ALREADY_SEGMENTED");
        }
        if (exists(
                "select 1 from group_message where group_id=? and epoch=? and event_seq is null limit 1",
                group,
                epoch)) {
            throw new RawArchiveException("RAW_SEQUENCE_MISSING");
        }
        if (exists(
                """
                select 1 from group_message where group_id=? and epoch=? and kind='TEXT' and occurred_at>? limit 1
                """,
                group,
                epoch,
                clock.instant())) {
            throw new RawArchiveException("RAW_MIGRATION_FUTURE_SOURCE");
        }
    }

    private boolean exists(String query, Object... arguments) {
        return db.find(query, (row, rowNumber) -> true, arguments).isPresent();
    }

    private long highSeq(RawPartition partition) {
        return db.find(
                        "select coalesce(max(event_seq),0) from group_message where group_id=? and epoch=?",
                        (row, rowNumber) -> row.getLong(1),
                        partition.groupId(),
                        partition.epoch())
                .orElseThrow();
    }

    private List<LegacyFile> validateLegacyFiles(RawPartition partition) {
        var files = new ArrayList<LegacyFile>();
        for (LegacyDocument document : verifier.loadMarkdownDocuments(partition)) {
            verifier.validateLegacyDocument(document);
            requireRetainedSources(partition, document.id());
            Path file = verifier.path(document);
            if (Files.exists(file)) {
                files.add(
                        new LegacyFile(
                                store.relative(file), RawArchiveMigrationStorage.hashFile(file)));
            }
        }
        return List.copyOf(files);
    }

    private void requireRetainedSources(RawPartition partition, UUID document) {
        if (exists(
                """
                select 1 from memory_document_source s join group_message m on m.id=s.message_id
                where s.document_id=? and (m.kind<>'TEXT' or m.group_id<>? or m.epoch<>?
                or m.event_seq is null or m.occurred_at<?) limit 1
                """,
                document,
                partition.groupId(),
                partition.epoch(),
                archive.archiveCutoff(clock.instant()))) {
            throw new RawArchiveException("RAW_MIGRATION_SOURCE_EXPIRED");
        }
    }

    private Map<String, Object> migrateSource(SourceSnapshot source) {
        Staging staging = prepareMigration(source);
        List<RawSegment> segments = stageSegments(staging);
        publishMigration(staging, segments);
        return verifyPublishedMigration(staging);
    }

    private Staging prepareMigration(SourceSnapshot source) {
        RawPartition partition = source.partition();
        UUID id =
                UUID.nameUUIDFromBytes(
                        (partition + ":" + source.fingerprint()).getBytes(StandardCharsets.UTF_8));
        var staging = new Staging(id, source, restoreStagedPaths(source));
        // SIDE EFFECT: 先持久化 PREPARED，重启后才能定位未发布的分段。
        db.exec(
                """
                insert into raw_archive_migration(id,group_id,epoch,source_fingerprint,state,manifest_json,updated_ms)
                values(?,?,?,?,'PREPARED',?,?) on conflict(id) do update set state='PREPARED',error_code=null
                """,
                id,
                partition.groupId(),
                partition.epoch(),
                source.fingerprint(),
                db.json(staging.manifest()),
                clock.millis());
        checkpoint("prepared");
        return staging;
    }

    private Set<String> restoreStagedPaths(SourceSnapshot source) {
        RawPartition partition = source.partition();
        return db.find(
                        """
                select source_fingerprint,manifest_json from raw_archive_migration
                where group_id=? and epoch=? and state='PREPARED'
                """,
                        PreparedRow::read,
                        partition.groupId(),
                        partition.epoch())
                .map(previous -> requireMatchingPreparation(source, previous))
                .orElseGet(LinkedHashSet::new);
    }

    private Set<String> requireMatchingPreparation(SourceSnapshot source, PreparedRow previous) {
        if (!source.fingerprint().equals(previous.fingerprint())) {
            throw new RawArchiveException("RAW_MIGRATION_SOURCE_CHANGED");
        }
        Manifest manifest = db.parse(previous.manifest(), Manifest.class);
        return manifest.stagedPaths() == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(manifest.stagedPaths());
    }

    private List<RawSegment> stageSegments(Staging staging) {
        SourceSnapshot source = staging.source();
        List<RawSegment> segments =
                storage.buildSegments(
                        source.partition(),
                        source.highSeq(),
                        segment -> recordStagedSegment(staging, segment));
        checkpoint("filesForced");
        if (!source.fingerprint()
                .equals(
                        storage.fingerprint(
                                source.partition(), source.highSeq(), source.oldFiles()))) {
            throw new RawArchiveException("RAW_MIGRATION_SOURCE_CHANGED");
        }
        return segments;
    }

    private void recordStagedSegment(Staging staging, RawSegment segment) {
        staging.stagedPaths().add(store.relative(store.path(segment, "log")));
        staging.stagedPaths().add(store.relative(store.path(segment, "index")));
        db.exec(
                "update raw_archive_migration set manifest_json=?,updated_ms=? where id=?",
                db.json(staging.manifest()),
                clock.millis(),
                staging.id());
    }

    private void publishMigration(Staging staging, List<RawSegment> segments) {
        SourceSnapshot source = staging.source();
        var plan =
                new RawArchiveMigrationPublication.Plan(
                        source.partition(), staging.id(), source.highSeq(), segments);
        publication.publish(
                plan,
                staging.stagedPaths(),
                db.json(staging.manifest()),
                () -> checkpoint("publishing"));
        checkpoint("published");
    }

    private Map<String, Object> verifyPublishedMigration(Staging staging) {
        SourceSnapshot source = staging.source();
        RawPartition partition = source.partition();
        Map<String, Object> verified = verify(partition.groupId());
        publication.markVerified(staging.id());
        checkpoint("verified");
        var result = new LinkedHashMap<>(verified);
        result.put("groupId", partition.groupId().toString());
        result.put("epoch", Long.toString(partition.epoch()));
        result.put("migrationId", staging.id().toString());
        result.put("fingerprint", source.fingerprint());
        result.put("state", "VERIFIED");
        return result;
    }

    private record PreparedRow(String fingerprint, String manifest) {
        static PreparedRow read(ResultSet row, int rowNumber) throws SQLException {
            return new PreparedRow(
                    row.getString("source_fingerprint"), row.getString("manifest_json"));
        }
    }

    private record SourceSnapshot(
            RawPartition partition, long highSeq, List<LegacyFile> oldFiles, String fingerprint) {}

    private record Staging(UUID id, SourceSnapshot source, Set<String> stagedPaths) {
        Manifest manifest() {
            return new Manifest(
                    Long.toString(source.highSeq()), source.oldFiles(), List.copyOf(stagedPaths));
        }
    }

    protected void checkpoint(String phase) {}

    /** 核对文件、源消息和引用；发布后的不一致以 RAW 错误码拒绝。 */
    public Map<String, Object> verify(UUID group) {
        return verifier.verify(group);
    }

    /** 验证已发布的群归档；为仍匹配源摘要的旧文件排队回收。 */
    public Map<String, Object> finalizeMigration(UUID group) {
        Objects.requireNonNull(group, "group must not be null");
        return store.locked(group, () -> finalizeVerifiedGroup(group));
    }

    private Map<String, Object> finalizeVerifiedGroup(UUID group) {
        verify(group);
        long files = publication.finalizeVerifiedGroup(group, () -> checkpoint("finalized"));
        return Map.of("ok", true, "queuedLegacyFiles", files);
    }

    /** 校验当前归档并导出 V9 副本。调用期间源实例必须停止写入。 */
    public Map<String, Object> exportLegacy(Path destination) {
        Objects.requireNonNull(destination, "destination must not be null");
        verify(null);
        return new RawArchiveLegacyExport(db, store, archive, clock).export(destination);
    }
}
