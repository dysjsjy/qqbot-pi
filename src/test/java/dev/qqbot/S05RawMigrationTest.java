/**
 * [INPUT]: 使用临时 SQLite、旧版 Markdown 和分段文件。
 * [OUTPUT]: 验证离线迁移恢复、校验失败、V9 分页导出与目标保护。
 * [POS]: RAW 迁移和旧版兼容的集成回归；不触碰实际归档。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.memory.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class S05RawMigrationTest {
    @TempDir Path directory;
    MemoryV2TestDatabase fixture;
    RawSegmentStore store;
    RawArchiveService service;
    RawArchiveMigration migration;
    UUID group, document;

    @BeforeEach
    void setup() {
        fixture = new MemoryV2TestDatabase(directory.resolve("migration.db"));
        group = fixture.group("legacy");
        store = new RawSegmentStore(directory, RawArchiveProperties.defaults(), new ObjectMapper());
        service =
                new RawArchiveService(
                        fixture.db,
                        store,
                        RawArchiveProperties.defaults(),
                        S03RawArchiveTest.CLOCK);
        migration =
                new RawArchiveMigration(
                        fixture.db,
                        store,
                        service,
                        RawArchiveProperties.defaults(),
                        S03RawArchiveTest.CLOCK);
        var files = new MarkdownMemory(fixture.db, S03RawArchiveTest.properties(directory));
        for (int seq = 1; seq <= 3; seq++) {
            UUID source =
                    fixture.message(
                            group,
                            "old-" + seq,
                            "sender",
                            "原文" + seq,
                            seq,
                            S03RawArchiveTest.NOW.minusSeconds(seq));
            UUID id = UUID.randomUUID();
            if (seq == 1) {
                document = id;
            }
            fixture.db.exec(
                    "insert into memory_document(id,group_id,epoch,kind,state,source_count) values(?,?,2,'RAW','READY',1)",
                    id,
                    group);
            fixture.db.exec(
                    "insert into memory_document_source(document_id,message_id,kind) values(?,?,'RAW')",
                    id,
                    source);
            files.writeDocument(
                    group,
                    id,
                    "RAW",
                    "# 群消息原样记录\n"
                            + RawRecord.from(
                                            fixture.db.one(
                                                    "select * from group_message where id=?",
                                                    source))
                                    .markdown());
        }
    }

    @AfterEach
    void close() {
        store.close();
        fixture.close();
    }

    @Test
    void migrationPreservesOldIdsAndFinalizationRemovesSmallFiles() throws Exception {
        assertEquals(true, migration.inspect(group).get("ok"));
        assertEquals(3L, migration.migrate(group).get("records"));
        assertTrue(
                service.readDocument(group, document, null, 100)
                        .get("content")
                        .toString()
                        .contains("原文1"));
        assertEquals(1, fixture.db.list("select id from raw_archive_segment").size());
        assertEquals(true, migration.migrate(group).get("reused"));
        migration.finalizeMigration(group);
        new RawArchiveCollector(fixture.db, store, service, S03RawArchiveTest.CLOCK).runOnce();
        try (var files =
                Files.list(directory.resolve("memory").resolve(group.toString()).resolve("raw"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void preparedMigrationResumesWithoutDuplicates() {
        var failed =
                new RawArchiveMigration(
                        fixture.db,
                        store,
                        service,
                        RawArchiveProperties.defaults(),
                        S03RawArchiveTest.CLOCK) {
                    @Override
                    protected void checkpoint(String phase) {
                        if (phase.equals("filesForced")) {
                            throw new RawArchiveException("INJECTED");
                        }
                    }
                };
        assertThrows(RawArchiveException.class, () -> failed.migrate(group));
        assertTrue(fixture.db.list("select id from raw_archive_segment").isEmpty());
        migration.migrate(group);
        assertEquals(3L, migration.verify(group).get("records"));
        assertEquals(
                3,
                fixture.db
                        .list("select message_id from memory_document_source where kind='RAW'")
                        .size());
    }

    @Test
    void publicationFailureRollsBackReferencesBeforeRetry() {
        var failed =
                new RawArchiveMigration(
                        fixture.db,
                        store,
                        service,
                        RawArchiveProperties.defaults(),
                        S03RawArchiveTest.CLOCK) {
                    @Override
                    protected void checkpoint(String phase) {
                        if (phase.equals("publishing")) {
                            throw new RawArchiveException("INJECTED");
                        }
                    }
                };

        var failure = assertThrows(RawArchiveException.class, () -> failed.migrate(group));

        assertEquals("INJECTED", failure.getMessage());
        assertTrue(fixture.db.list("select id from raw_archive_segment").isEmpty());
        assertTrue(fixture.db.list("select segment_id from raw_archive_ref").isEmpty());
        assertTrue(fixture.db.list("select group_id from raw_archive_state").isEmpty());
        assertEquals(
                "PREPARED", fixture.db.one("select state from raw_archive_migration").get("state"));
        assertEquals(3L, migration.migrate(group).get("records"));
    }

    @Test
    void publishedMigrationResumesAndExportsNewMessagesToV9() throws Exception {
        var failed =
                new RawArchiveMigration(
                        fixture.db,
                        store,
                        service,
                        RawArchiveProperties.defaults(),
                        S03RawArchiveTest.CLOCK) {
                    @Override
                    protected void checkpoint(String phase) {
                        if (phase.equals("published")) {
                            throw new RawArchiveException("INJECTED");
                        }
                    }
                };
        assertThrows(RawArchiveException.class, () -> failed.migrate(group));
        assertEquals(true, migration.migrate(group).get("reused"));
        fixture.message(group, "new", "sender", "迁移后新消息", 5, S03RawArchiveTest.NOW);
        assertEquals(1, service.archiveBatch(group));
        new MemoryPolicyService(fixture.db).updateSettings(group, 0, "RAW", 2, 7100, 45);
        Path exported = directory.resolve("exported");
        assertEquals(4L, migration.exportLegacy(exported).get("rawMessages"));
        try (var old = new MemoryV2TestDatabase(exported.resolve("qqbot.db"), "9")) {
            assertEquals(
                    4,
                    old.db
                            .list("select message_id from memory_document_source where kind='RAW'")
                            .size());
            assertEquals(0, old.db.jdbc.queryForList("pragma foreign_key_check").size());
            assertEquals(
                    "9",
                    old.db
                            .one(
                                    "select version from flyway_schema_history order by installed_rank desc limit 1")
                            .get("version"));
            assertNotNull(old.db.one("select id from memory_document where id=?", document));
            assertEquals(
                    "BUDGETED",
                    old.db
                            .one("select context_mode from memory_policy where group_id=?", group)
                            .get("context_mode"));
            assertEquals(
                    new MemoryPolicyService(fixture.db).settings(group),
                    new MemoryPolicyService(old.db).settings(group));
        }
        try (var files = Files.walk(exported.resolve("memory"))) {
            assertEquals(4, files.filter(path -> path.toString().endsWith(".md")).count());
        }
    }

    @Test
    void exportLegacy_partitionsLargeDocumentAndWritesManifest() throws Exception {
        for (int sequence = 4; sequence <= 104; sequence++) {
            fixture.message(
                    group,
                    "message-" + sequence,
                    "sender",
                    "按序导出",
                    sequence,
                    S03RawArchiveTest.NOW);
        }
        migration.migrate(group);
        Path exported = directory.resolve("paged-export");

        Map<String, Object> report = migration.exportLegacy(exported);

        assertEquals(104L, report.get("rawMessages"));
        assertEquals(5L, report.get("rawDocuments"));
        var manifest =
                new ObjectMapper().readTree(exported.resolve("export-manifest.json").toFile());
        assertEquals(5, manifest.path("mappings").size());
        try (var old = new MemoryV2TestDatabase(exported.resolve("qqbot.db"), "9")) {
            assertEquals(
                    List.of(1, 1, 1, 1, 100),
                    old.db.jdbc.queryForList(
                            "select source_count from memory_document where kind='RAW' order by source_count",
                            Integer.class));
            assertTrue(old.db.jdbc.queryForList("pragma foreign_key_check").isEmpty());
        }
    }

    @Test
    void exportLegacy_rejectsExistingTargetWithoutOverwriting() throws Exception {
        Path existing = Files.createDirectory(directory.resolve("exists"));
        Path existingDatabase = Files.writeString(existing.resolve("qqbot.db"), "keep");

        var failure =
                assertThrows(RawArchiveException.class, () -> migration.exportLegacy(existing));

        assertEquals("RAW_EXPORT_DESTINATION_INVALID", failure.getMessage());
        assertEquals("keep", Files.readString(existingDatabase));
        assertFalse(Files.exists(existing.resolve("export-manifest.json")));
    }

    @Test
    void contentMismatchStopsBeforePublishing() throws Exception {
        Path file =
                directory
                        .resolve("memory")
                        .resolve(group.toString())
                        .resolve("raw")
                        .resolve(document + ".md");
        Files.writeString(file, "edited");
        assertEquals(false, migration.inspect(group).get("ok"));
        assertThrows(RawArchiveException.class, () -> migration.migrate(group));
        assertTrue(fixture.db.list("select id from raw_archive_segment").isEmpty());
    }

    @Test
    void finalizeRefusesChangedLegacyFile() throws Exception {
        migration.migrate(group);
        Path file =
                directory
                        .resolve("memory")
                        .resolve(group.toString())
                        .resolve("raw")
                        .resolve(document + ".md");
        Files.writeString(file, "edited");
        assertThrows(RawArchiveException.class, () -> migration.finalizeMigration(group));
        assertTrue(Files.exists(file));
    }

    @Test
    void collectorRefusesFileChangedAfterFinalizationWasQueued() throws Exception {
        migration.migrate(group);
        migration.finalizeMigration(group);
        Path file =
                directory
                        .resolve("memory")
                        .resolve(group.toString())
                        .resolve("raw")
                        .resolve(document + ".md");
        Files.writeString(file, "edited after finalize");
        new RawArchiveCollector(fixture.db, store, service, S03RawArchiveTest.CLOCK).runOnce();
        assertTrue(Files.exists(file));
        assertEquals(
                "RAW_MIGRATION_SOURCE_CHANGED",
                fixture.db
                        .one("select error_code from raw_archive_gc where state='FAILED'")
                        .get("error_code"));
    }

    @Test
    void clearAlsoCollectsMigratedLegacyFilesBeforeFinalization() throws Exception {
        migration.migrate(group);
        new MarkdownMemory(fixture.db, S03RawArchiveTest.properties(directory)).delete(group);
        fixture.db.exec("delete from memory_document where group_id=?", group);
        fixture.db.exec("delete from raw_archive_segment where group_id=?", group);
        var collector =
                new RawArchiveCollector(fixture.db, store, service, S03RawArchiveTest.CLOCK);
        collector.runOnce();
        try (var files = Files.walk(directory.resolve("memory").resolve(group.toString()))) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }
}
