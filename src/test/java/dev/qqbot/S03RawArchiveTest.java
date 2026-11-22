/**
 * [INPUT]: 使用临时 SQLite、固定时钟与分段文件。
 * [OUTPUT]: 验证追加、分页、作用域隔离和过期边界。
 * [POS]: RAW 服务集成回归；时钟只作用于测试归档。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.memory.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class S03RawArchiveTest {
    static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    @TempDir Path directory;
    MemoryV2TestDatabase fixture;
    RawSegmentStore store;
    RawArchiveService service;
    UUID group;

    @BeforeEach
    void setup() {
        fixture = new MemoryV2TestDatabase(directory.resolve("raw.db"));
        group = fixture.group("raw");
        store = new RawSegmentStore(directory, RawArchiveProperties.defaults(), new ObjectMapper());
        service = new RawArchiveService(fixture.db, store, RawArchiveProperties.defaults(), CLOCK);
    }

    @AfterEach
    void close() {
        store.close();
        fixture.close();
    }

    UUID message(long seq) {
        return fixture.message(
                group, "message-" + seq, "sender", "原文😀\n" + seq, seq, NOW.minusSeconds(60));
    }

    UUID document() {
        return (UUID)
                fixture.db
                        .one(
                                "select id from memory_document where group_id=? and kind='RAW'",
                                group)
                        .get("id");
    }

    @Test
    void sameDocumentGrowsAndPaginationKeepsSnapshot() {
        UUID first = message(1);
        assertEquals(1, service.archiveBatch(group));
        UUID document = document();
        message(3);
        message(4);
        assertEquals(2, service.archiveBatch(group));
        assertEquals(document, document());
        assertEquals(0, service.archiveBatch(group));
        var page = service.readDocument(group, document, null, 1);
        assertEquals(3L, page.get("totalSources"));
        assertTrue(page.get("content").toString().contains(first.toString()));
        message(6);
        service.archiveBatch(group);
        var second = service.readDocument(group, document, (String) page.get("nextCursor"), 100);
        assertEquals(2, ((List<?>) second.get("sources")).size());
        assertNull(second.get("nextCursor"));
        assertFalse(second.get("content").toString().contains("原文😀\n6"));
    }

    @Test
    void readDocument_rejectsMalformedAndReversedCursors() {
        message(1);
        message(2);
        service.archiveBatch(group);
        UUID document = document();
        var first = service.readDocument(group, document, null, 1);
        String position = (String) first.get("nextCursor");
        String json = new String(Base64.getUrlDecoder().decode(position), StandardCharsets.UTF_8);
        String reversed =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                json.replace("\"after\":\"1\"", "\"after\":\"3\"")
                                        .getBytes(StandardCharsets.UTF_8));

        for (String invalid : List.of("!", reversed)) {
            var failure =
                    assertThrows(
                            ResponseStatusException.class,
                            () -> service.readDocument(group, document, invalid, 1));
            assertEquals(400, failure.getStatusCode().value());
            assertEquals("RAW_CURSOR_INVALID", failure.getReason());
        }
    }

    @Test
    void futureTextIsBarrierButKindsAndSequenceGapsAreNot() {
        message(1);
        UUID command = message(2);
        fixture.db.exec("update group_message set kind='COMMAND' where id=?", command);
        UUID future = message(4);
        fixture.db.exec(
                "update group_message set occurred_at=? where id=?", NOW.plusSeconds(1), future);
        message(7);
        assertEquals(1, service.archiveBatch(group));
        assertEquals("2", service.status(group).lastScannedSeq());
        fixture.db.exec("update group_message set occurred_at=? where id=?", NOW, future);
        assertEquals(2, service.archiveBatch(group));
        assertEquals("7", service.status(group).lastScannedSeq());
    }

    @Test
    void commitFailureReplaysForcedTailWithoutDuplicates() {
        message(1);
        service.archiveBatch(group);
        message(2);
        var failing =
                new RawArchiveService(fixture.db, store, RawArchiveProperties.defaults(), CLOCK) {
                    @Override
                    protected void checkpoint(String phase) {
                        if (phase.equals("inCommit")) {
                            throw new RawArchiveException("INJECTED");
                        }
                    }
                };
        assertThrows(RawArchiveException.class, () -> failing.archiveBatch(group));
        assertEquals("1", service.status(group).lastScannedSeq());
        service.retry(group);
        assertEquals(1, service.archiveBatch(group));
        assertEquals(
                2L,
                RawArchiveService.number(
                        fixture.db.one(
                                "select count(*) n from memory_document_source where kind='RAW'"),
                        "n"));
    }

    @Test
    void epochChangeBeforeCommitCannotResurrectOldBatch() {
        message(1);
        var failing =
                new RawArchiveService(fixture.db, store, RawArchiveProperties.defaults(), CLOCK) {
                    @Override
                    protected void checkpoint(String phase) {
                        if (phase.equals("beforeCommit")) {
                            fixture.db.exec(
                                    "update group_config set epoch=epoch+1 where id=?", group);
                        }
                    }
                };
        assertThrows(RawArchiveException.class, () -> failing.archiveBatch(group));
        assertNull(fixture.db.one("select id from memory_document"));
    }

    @Test
    void legacyNeedsMigrationAndCannotBeRetriedPastBarrier() {
        UUID source = message(1), document = UUID.randomUUID();
        fixture.db.exec(
                "insert into memory_document(id,group_id,epoch,kind,state,source_count) values(?,?,2,'RAW','READY',1)",
                document,
                group);
        fixture.db.exec(
                "insert into memory_document_source(document_id,message_id,kind) values(?,?,'RAW')",
                document,
                source);
        assertEquals(0, service.archiveBatch(group));
        assertEquals("NEEDS_MIGRATION", service.status(group).status());
        assertThrows(ResponseStatusException.class, () -> service.retry(group));
    }

    @Test
    void pageCannotCrossGroupOrReadAfterSourcesExpire() {
        var finiteArchive =
                new RawArchiveService(
                        fixture.db,
                        store,
                        RawArchiveProperties.defaults(),
                        CLOCK,
                        new RetentionProperties(30, 30, 30));
        message(1);
        UUID second = message(2);
        finiteArchive.archiveBatch(group);
        var page = finiteArchive.readDocument(group, document(), null, 1);
        UUID other = fixture.group("other");
        assertThrows(
                ResponseStatusException.class,
                () -> finiteArchive.readDocument(other, document(), null, 10));
        fixture.db.exec(
                "update group_message set occurred_at=? where id=?",
                NOW.minus(Duration.ofDays(31)),
                second);
        var failure =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                finiteArchive.readDocument(
                                        group, document(), (String) page.get("nextCursor"), 1));
        assertEquals(409, failure.getStatusCode().value());
    }

    @Test
    void timeRollCreatesSecondSegmentAndNoEmptySegments() {
        message(1);
        service.archiveBatch(group);
        fixture.db.exec(
                "update raw_archive_segment set opened_ms=?",
                NOW.minus(Duration.ofDays(2)).toEpochMilli());
        message(2);
        service.archiveBatch(group);
        assertEquals(2, fixture.db.list("select id from raw_archive_segment").size());
        service.archiveBatch(group);
        assertEquals(2, fixture.db.list("select id from raw_archive_segment").size());
    }

    @Test
    void expiryRewritesOnlySurvivingRecordsAndClearIsDeferred() throws Exception {
        UUID first = message(1);
        fixture.db.exec(
                "update group_message set occurred_at=? where id=?",
                NOW.minus(Duration.ofDays(29)),
                first);
        message(2);
        service.archiveBatch(group);
        Clock later = Clock.fixed(NOW.plus(Duration.ofDays(2)), ZoneOffset.UTC);
        var bot = properties(directory);
        var files = new MarkdownMemory(fixture.db, bot);
        var archive =
                new MarkdownArchive(
                        fixture.db,
                        files,
                        new MemoryPolicyService(fixture.db),
                        MemoryProperties.defaults(),
                        later);
        archive.setRawArchive(service);
        fixture.db.tx(
                () -> {
                    archive.expire(group, later.instant().minus(Duration.ofDays(30)));
                    fixture.db.exec("delete from group_message where id=?", first);
                    return null;
                });
        var collector = new RawArchiveCollector(fixture.db, store, service, later);
        collector.runOnce();
        collector.runOnce();
        var segment = RawSegment.from(fixture.db.one("select * from raw_archive_segment"));
        assertEquals(1, segment.generation());
        assertEquals(1, store.read(segment, 0, 100, 100).size());
        files.write(group, "manual");
        fixture.db.tx(
                () -> {
                    archive.clear(group);
                    fixture.db.exec("update group_config set epoch=epoch+1 where id=?", group);
                    return null;
                });
        assertEquals("", files.read(group));
        files.write(group, "new manual");
        collector.runOnce();
        assertEquals("new manual", files.read(group));
        assertFalse(Files.exists(store.path(segment, "log")));
    }

    @Test
    void rollbackKeepsFilesAndDoesNotPublishGc() {
        message(1);
        service.archiveBatch(group);
        var files = new MarkdownMemory(fixture.db, properties(directory));
        files.write(group, "manual");
        assertThrows(
                IllegalStateException.class,
                () ->
                        fixture.db.tx(
                                () -> {
                                    files.delete(group);
                                    throw new IllegalStateException("rollback");
                                }));
        assertEquals("manual", files.read(group));
        assertTrue(fixture.db.list("select id from raw_archive_gc").isEmpty());
    }

    @Test
    void pausedArchiveCountsMessagesThatExpireBeforeAnyWorkerRun() {
        UUID source = message(1);
        fixture.db.exec(
                "update group_message set occurred_at=? where id=?",
                NOW.minus(Duration.ofDays(31)),
                source);
        var defaults = RawArchiveProperties.defaults();
        var paused =
                new RawArchiveProperties(
                        false,
                        defaults.segmentBytes(),
                        defaults.segmentMaxAgeSeconds(),
                        defaults.pollMs(),
                        defaults.batchMessages(),
                        defaults.batchBytes(),
                        defaults.indexIntervalBytes(),
                        defaults.maxOpenSegments(),
                        defaults.gcPollMs());
        var raw = new RawArchiveService(fixture.db, store, paused, CLOCK);
        var archive =
                new MarkdownArchive(
                        fixture.db,
                        new MarkdownMemory(fixture.db, properties(directory)),
                        new MemoryPolicyService(fixture.db),
                        MemoryProperties.defaults(),
                        CLOCK);
        archive.setRawArchive(raw);
        fixture.db.tx(
                () -> {
                    archive.expire(group, NOW.minus(Duration.ofDays(30)));
                    fixture.db.exec("delete from group_message where id=?", source);
                    return null;
                });
        assertEquals(1L, raw.status(group).expiredBeforeArchive());
        assertEquals("PAUSED", raw.status(group).status());
        assertEquals("ACTIVE", service.status(group).status());
    }

    @Test
    void failureOnSecondSegmentDoesNotPublishEitherHalfOfBatch() {
        var defaults = RawArchiveProperties.defaults();
        var small =
                new RawArchiveProperties(
                        true,
                        8L * 1024 * 1024,
                        defaults.segmentMaxAgeSeconds(),
                        defaults.pollMs(),
                        defaults.batchMessages(),
                        8 * 1024 * 1024,
                        defaults.indexIntervalBytes(),
                        defaults.maxOpenSegments(),
                        defaults.gcPollMs());
        var compact = new RawArchiveService(fixture.db, store, small, CLOCK);
        String body = "x".repeat(950000);
        fixture.db.tx(
                () -> {
                    for (int seq = 1; seq <= 7; seq++) {
                        fixture.message(
                                group, "large-" + seq, "A", body, seq, NOW.minusSeconds(30));
                    }
                    return null;
                });
        assertEquals(7, compact.archiveBatch(group));
        fixture.db.tx(
                () -> {
                    for (int seq = 8; seq <= 10; seq++) {
                        fixture.message(
                                group, "large-" + seq, "A", body, seq, NOW.minusSeconds(30));
                    }
                    return null;
                });
        try (var failingStore =
                new RawSegmentStore(directory, small, new ObjectMapper()) {
                    int forced;

                    @Override
                    protected void checkpoint(String phase) {
                        if (phase.equals("beforeForce") && ++forced == 2) {
                            throw new RawArchiveException("INJECTED_SECOND_SEGMENT");
                        }
                    }
                }) {
            assertThrows(
                    RawArchiveException.class,
                    () ->
                            new RawArchiveService(fixture.db, failingStore, small, CLOCK)
                                    .archiveBatch(group));
        }
        assertEquals("7", compact.status(group).lastScannedSeq());
        assertEquals(7L, compact.status(group).archivedMessages());
        assertEquals(1, fixture.db.list("select id from raw_archive_segment").size());
        compact.retry(group);
        assertEquals(3, compact.archiveBatch(group));
        assertEquals(2, fixture.db.list("select id from raw_archive_segment").size());
        assertEquals(
                10L,
                new RawArchiveMigration(fixture.db, store, compact, small, CLOCK)
                        .verify(group)
                        .get("records"));
    }

    static BotProperties properties(Path directory) {
        return new BotProperties(
                "admin",
                "",
                directory,
                false,
                new BotProperties.Pi("node", "unused", "test", "test", "KEY", "", 5, ""),
                null,
                new BotProperties.Qq(
                        false,
                        new BotProperties.OfficialQq(
                                "test", "", "https://example.com", "https://example.com", false)),
                new BotProperties.Gateway(false, "", "", "test", ""),
                new BotProperties.Rag(false));
    }
}
