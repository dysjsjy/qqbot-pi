package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.memory.*;
import dev.qqbot.model.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class S06RawLifecycleTest {
    @TempDir Path directory;

    @Test
    void failedPhysicalDeleteRetriesAfterRestartWithoutRestoringVisibility() throws Exception {
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("gc.db"));
                var store =
                        new RawSegmentStore(
                                directory, RawArchiveProperties.defaults(), new ObjectMapper()) {
                            boolean failed;

                            @Override
                            public synchronized void delete(String path) {
                                if (!failed) {
                                    failed = true;
                                    throw new RawArchiveException("RAW_GC_IO");
                                }
                                super.delete(path);
                            }
                        }) {
            UUID group = fixture.group("gc");
            var files = new MarkdownMemory(fixture.db, S03RawArchiveTest.properties(directory));
            files.write(group, "manual");
            fixture.db.tx(
                    () -> {
                        files.delete(group);
                        return null;
                    });
            assertEquals("", files.read(group));
            var service =
                    new RawArchiveService(
                            fixture.db,
                            store,
                            RawArchiveProperties.defaults(),
                            S03RawArchiveTest.CLOCK);
            new RawArchiveCollector(fixture.db, store, service, S03RawArchiveTest.CLOCK).runOnce();
            assertNotNull(fixture.db.one("select id from raw_archive_gc where state='FAILED'"));
            assertEquals("", files.read(group));
            try (var reopened =
                    new RawSegmentStore(
                            directory, RawArchiveProperties.defaults(), new ObjectMapper())) {
                new RawArchiveCollector(
                                fixture.db,
                                reopened,
                                service,
                                Clock.offset(S03RawArchiveTest.CLOCK, Duration.ofSeconds(2)))
                        .runOnce();
            }
            assertNull(fixture.db.one("select id from raw_archive_gc where state<>'DONE'"));
            assertFalse(
                    Files.exists(
                            directory
                                    .resolve("memory")
                                    .resolve(group.toString())
                                    .resolve("MEMORY.md")));
        }
    }

    @Test
    void corruptedSealedReadPersistsBrokenStateAndCannotBeRetried() throws Exception {
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("corrupt.db"));
                var store =
                        new RawSegmentStore(
                                directory, RawArchiveProperties.defaults(), new ObjectMapper())) {
            UUID group = fixture.group("corrupt");
            fixture.message(group, "one", "A", "原文", 1, S03RawArchiveTest.NOW.minusSeconds(30));
            var service =
                    new RawArchiveService(
                            fixture.db,
                            store,
                            RawArchiveProperties.defaults(),
                            S03RawArchiveTest.CLOCK);
            service.archiveBatch(group);
            RawSegment segment =
                    RawSegment.from(fixture.db.one("select * from raw_archive_segment"));
            fixture.db.exec("update raw_archive_segment set state='SEALED'");
            try (var channel =
                    java.nio.channels.FileChannel.open(
                            store.path(segment, "log"), StandardOpenOption.WRITE)) {
                channel.position(90);
                channel.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
                channel.force(true);
            }
            UUID document = (UUID) fixture.db.one("select id from memory_document").get("id");
            assertEquals(
                    503,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () -> service.readDocument(group, document, null, 100))
                            .getStatusCode()
                            .value());
            assertEquals(
                    "BROKEN", fixture.db.one("select state from raw_archive_segment").get("state"));
            assertEquals("RAW_ARCHIVE_CORRUPT", service.status(group).errorCode());
            assertThrows(ResponseStatusException.class, () -> service.retry(group));
        }
    }

    @Test
    void rawProgressesWhileSummaryModelIsBlocked() throws Exception {
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("independent.db"));
                var store =
                        new RawSegmentStore(
                                directory, RawArchiveProperties.defaults(), new ObjectMapper())) {
            UUID group = fixture.group("independent");
            fixture.db.tx(
                    () -> {
                        for (int seq = 1; seq <= 100; seq++) {
                            fixture.message(
                                    group,
                                    "old-" + seq,
                                    "A",
                                    "原文 " + seq,
                                    seq,
                                    S03RawArchiveTest.NOW.minusSeconds(60));
                        }
                        return null;
                    });
            var files = new MarkdownMemory(fixture.db, S03RawArchiveTest.properties(directory));
            var policies = new MemoryPolicyService(fixture.db);
            policies.configureLegacy(group, "SUMMARY");
            var summary =
                    new MarkdownArchive(
                            fixture.db,
                            files,
                            policies,
                            MemoryProperties.defaults(),
                            S03RawArchiveTest.CLOCK);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var model = mock(TextGenerator.class);
            when(model.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                    .thenAnswer(
                            call -> {
                                entered.countDown();
                                assertTrue(release.await(10, TimeUnit.SECONDS));
                                return new TextGenerator.Result("合成摘要", null);
                            });
            var worker = new MarkdownMemoryWorker(summary, model);
            try {
                worker.tick();
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                fixture.message(
                        group,
                        "new",
                        "A",
                        "模型等待期间的新消息",
                        101,
                        S03RawArchiveTest.NOW.minusSeconds(1));
                var service =
                        new RawArchiveService(
                                fixture.db,
                                store,
                                RawArchiveProperties.defaults(),
                                S03RawArchiveTest.CLOCK);
                assertTimeout(
                        Duration.ofSeconds(3),
                        () -> assertEquals(101, service.archiveBatch(group)));
                assertEquals(101L, service.status(group).archivedMessages());
            } finally {
                release.countDown();
                worker.close();
            }
        }
    }
}
