/**
 * [INPUT]: 接收已校验的迁移计划、暂存路径和分段文件。
 * [OUTPUT]: 原子发布分段引用、水位及迁移状态；排队回收未使用文件。
 * [POS]: 离线迁移的写库阶段；调用方持有群归档锁。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.memory.RawArchiveMigrationStorage.LegacyFile;
import dev.qqbot.memory.RawArchiveMigrationStorage.Manifest;
import dev.qqbot.persistence.Db;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class RawArchiveMigrationPublication {
    private static final int SOURCE_PAGE_SIZE = 500;
    private static final String MISSING_SOURCES =
            """
            select m.id from group_message m where m.group_id=? and m.epoch=? and m.kind='TEXT'
            and m.event_seq>=? and m.event_seq<=? and m.occurred_at>=? and m.occurred_at<=?
            and not exists(select 1 from memory_document_source s where s.message_id=m.id and s.kind='RAW')
            order by m.event_seq limit ?
            """;

    private final Db db;
    private final RawSegmentStore store;
    private final RawArchiveService archive;
    private final Clock clock;

    RawArchiveMigrationPublication(
            Db db, RawSegmentStore store, RawArchiveService archive, Clock clock) {
        this.db = db;
        this.store = store;
        this.archive = archive;
        this.clock = clock;
    }

    void publish(Plan plan, Collection<String> staged, String manifest, Runnable beforeCommit) {
        List<String> abandoned = abandonedPaths(plan.segments(), staged);
        db.tx(() -> publishInTransaction(plan, abandoned, manifest, beforeCommit));
    }

    private List<String> abandonedPaths(List<RawSegment> segments, Collection<String> staged) {
        var retained = new HashSet<String>();
        for (RawSegment segment : segments) {
            retained.add(store.relative(store.path(segment, "log")));
            retained.add(store.relative(store.path(segment, "index")));
        }
        return staged.stream().filter(path -> !retained.contains(path)).toList();
    }

    private Void publishInTransaction(
            Plan plan, List<String> abandoned, String manifest, Runnable beforeCommit) {
        requireCurrentEpoch(plan.partition());
        // SIDE EFFECT: 引用、回收任务、水位与状态由同一事务提交或回滚。
        for (RawSegment segment : plan.segments()) {
            publishSegment(segment);
        }
        enqueueCleanup(plan.partition(), abandoned);
        markPublished(plan, manifest);
        beforeCommit.run();
        return null;
    }

    private void requireCurrentEpoch(RawPartition partition) {
        long current =
                db.find(
                                "select epoch from group_config where id=?",
                                (row, rowNumber) -> row.getLong("epoch"),
                                partition.groupId())
                        .orElseThrow(() -> new RawArchiveException("RAW_SCOPE_CHANGED"));
        if (current != partition.epoch()) {
            throw new RawArchiveException("RAW_SCOPE_CHANGED");
        }
    }

    private void publishSegment(RawSegment segment) {
        archive.publishSegment(segment.sealed());
        linkExistingDocuments(segment);
        addMissingSources(segment);
    }

    private void linkExistingDocuments(RawSegment segment) {
        RawPartition partition = segment.partition();
        db.exec(
                """
                insert into raw_archive_ref(document_id,segment_id)
                select distinct d.id,? from memory_document d join memory_document_source s on s.document_id=d.id
                join group_message m on m.id=s.message_id where d.group_id=? and d.epoch=? and d.kind='RAW'
                and m.event_seq>=? and m.event_seq<=?
                """,
                segment.id(),
                partition.groupId(),
                partition.epoch(),
                segment.baseSeq(),
                segment.lastSeq());
    }

    private void addMissingSources(RawSegment segment) {
        List<UUID> page = readMissingSourceIds(segment);
        if (page.isEmpty()) {
            return;
        }
        // SIDE EFFECT: 一个分段的缺失来源共用一个补建文档。
        UUID document = createDocument(segment);
        while (!page.isEmpty()) {
            insertSources(document, page);
            page = readMissingSourceIds(segment);
        }
    }

    private List<UUID> readMissingSourceIds(RawSegment segment) {
        RawPartition partition = segment.partition();
        return db.query(
                MISSING_SOURCES,
                (row, rowNumber) -> UUID.fromString(row.getString("id")),
                partition.groupId(),
                partition.epoch(),
                segment.baseSeq(),
                segment.lastSeq(),
                archive.archiveCutoff(clock.instant()),
                clock.instant(),
                SOURCE_PAGE_SIZE);
    }

    private UUID createDocument(RawSegment segment) {
        UUID id = UUID.randomUUID();
        RawPartition partition = segment.partition();
        db.exec(
                """
                insert into memory_document(id,group_id,epoch,kind,state,source_count,storage,created_at,finished_at)
                values(?,?,?,'RAW','READY',0,'SEGMENT_V1',?,?)
                """,
                id,
                partition.groupId(),
                partition.epoch(),
                clock.instant(),
                clock.instant());
        db.exec(
                "insert into raw_archive_ref(document_id,segment_id) values(?,?)",
                id,
                segment.id());
        return id;
    }

    private void insertSources(UUID document, List<UUID> sourceIds) {
        for (UUID source : sourceIds) {
            db.exec(
                    """
                    insert into memory_document_source(document_id,message_id,kind) values(?,?,'RAW')
                    """,
                    document,
                    source);
        }
        db.exec(
                "update memory_document set source_count=source_count+? where id=?",
                sourceIds.size(),
                document);
    }

    private void enqueueCleanup(RawPartition partition, List<String> abandoned) {
        if (!abandoned.isEmpty()) {
            RawArchiveCollector.enqueue(
                    db,
                    partition.groupId(),
                    partition.epoch(),
                    "DELETE_FILES",
                    Map.of("paths", abandoned),
                    clock.millis());
        }
    }

    private void markPublished(Plan plan, String manifest) {
        RawPartition partition = plan.partition();
        db.exec(
                """
                update memory_document set storage='SEGMENT_V1',state='READY',error_code=null,finished_at=?
                where group_id=? and epoch=? and kind='RAW'
                """,
                clock.instant(),
                partition.groupId(),
                partition.epoch());
        db.exec(
                """
                insert into raw_archive_state(group_id,epoch,last_scanned_seq,status,updated_ms)
                values(?,?,?,'ACTIVE',?) on conflict(group_id,epoch) do update set
                    last_scanned_seq=excluded.last_scanned_seq,status='ACTIVE',error_code=null,
                    attempts=0,next_retry_ms=null,updated_ms=excluded.updated_ms
                """,
                partition.groupId(),
                partition.epoch(),
                plan.highSeq(),
                clock.millis());
        db.exec(
                """
                update raw_archive_migration set state='PUBLISHED',manifest_json=?,updated_ms=? where id=?
                """,
                manifest,
                clock.millis(),
                plan.migrationId());
    }

    Optional<PublishedMigration> findPublishedMigration(RawPartition partition) {
        return db.find(
                """
                select id,state from raw_archive_migration where group_id=? and epoch=?
                and state in ('PUBLISHED','VERIFIED','FINALIZED') order by updated_ms desc limit 1
                """,
                RawArchiveMigrationPublication::readPublishedMigration,
                partition.groupId(),
                partition.epoch());
    }

    private static PublishedMigration readPublishedMigration(ResultSet row, int rowNumber)
            throws SQLException {
        return new PublishedMigration(
                UUID.fromString(row.getString("id")), "FINALIZED".equals(row.getString("state")));
    }

    void markVerified(UUID migration) {
        db.exec(
                "update raw_archive_migration set state='VERIFIED',updated_ms=? where id=?",
                clock.millis(),
                migration);
    }

    long finalizeVerifiedGroup(UUID group, Runnable afterCommit) {
        List<Cleanup> migrations =
                db.query(
                        """
                select id,epoch,manifest_json from raw_archive_migration
                where group_id=? and state in ('PUBLISHED','VERIFIED')
                """,
                        RawArchiveMigrationPublication::readCleanup,
                        group);
        long fileCount = 0;
        for (Cleanup migration : migrations) {
            List<LegacyFile> files = requireUnchangedLegacyFiles(migration);
            db.tx(() -> finalizeInTransaction(group, migration, files));
            fileCount += files.size();
            afterCommit.run();
        }
        return fileCount;
    }

    private static Cleanup readCleanup(ResultSet row, int rowNumber) throws SQLException {
        return new Cleanup(
                UUID.fromString(row.getString("id")),
                row.getLong("epoch"),
                row.getString("manifest_json"));
    }

    private List<LegacyFile> requireUnchangedLegacyFiles(Cleanup migration) {
        Manifest manifest = db.parse(migration.manifest(), Manifest.class);
        for (LegacyFile file : manifest.oldFiles()) {
            Path path = store.resolve(file.path());
            if (Files.exists(path)
                    && !file.sha256().equals(RawArchiveMigrationStorage.hashFile(path))) {
                throw new RawArchiveException("RAW_MIGRATION_SOURCE_CHANGED");
            }
        }
        return List.copyOf(manifest.oldFiles());
    }

    private Void finalizeInTransaction(UUID group, Cleanup migration, List<LegacyFile> files) {
        // SIDE EFFECT: 最终状态与异步删除任务一起提交，不直接删除文件。
        RawArchiveCollector.enqueue(
                db,
                group,
                migration.epoch(),
                "DELETE_FILES",
                Map.of("hashedPaths", files),
                clock.millis());
        db.exec(
                "update raw_archive_migration set state='FINALIZED',updated_ms=? where id=?",
                clock.millis(),
                migration.id());
        return null;
    }

    record PublishedMigration(UUID id, boolean isFinalized) {}

    private record Cleanup(UUID id, long epoch, String manifest) {}

    record Plan(RawPartition partition, UUID migrationId, long highSeq, List<RawSegment> segments) {
        Plan {
            segments = List.copyOf(segments);
        }
    }
}
