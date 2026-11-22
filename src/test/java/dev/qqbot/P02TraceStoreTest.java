package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.qqbot.persistence.Db;
import dev.qqbot.trace.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P02TraceStoreTest {
    @TempDir Path directory;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC);

    private SpanRecord record(UUID run, long start, long duration) {
        return new SpanRecord(
                UUID.randomUUID(),
                run,
                null,
                null,
                "AGENT",
                "agent.run",
                0,
                "OK",
                null,
                start,
                duration,
                30,
                40,
                50,
                10L,
                null,
                2L,
                null,
                null,
                null,
                Map.of("count", 1));
    }

    @Test
    void bufferedRoundtripQueriesAndRetention() {
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("store.db"))) {
            var store = new TraceStore(fixture.db, true, 7, clock);
            var queries = new TraceQueries(fixture.db, clock);
            UUID run = UUID.randomUUID();
            long start = clock.millis() * 1000;
            store.accept(
                    List.of(
                            record(run, start, 100),
                            record(run, start + 100, 200),
                            record(run, start + 200, 300),
                            record(run, start + 300, 400)));
            assertEquals(4, store.pending());
            assertTrue(fixture.db.list("select * from trace_span").isEmpty());
            store.flush();
            assertEquals(0, store.pending());
            var roots = queries.list(null, "AGENT", 50, 0);
            assertEquals(4, roots.size());
            assertEquals(start + 300, roots.get(0).startUs());
            assertEquals(Map.of("count", 1), roots.get(0).attributes());
            var detail = queries.detail(run);
            assertEquals(start, detail.startUs());
            assertEquals(start + 700, detail.endUs());
            assertEquals(10L, roots.get(0).estimatedInputTokens());
            assertNull(roots.get(0).inputTokens());
            var stat = queries.stats(24).get(0);
            assertEquals(1000L, stat.totalUs());
            assertEquals(200L, stat.p50Us());
            assertEquals(400L, stat.p95Us());
            assertThrows(IllegalArgumentException.class, () -> queries.list(null, "BAD", 50, 0));
            store.accept(
                    List.of(
                            record(
                                    UUID.randomUUID(),
                                    start - Duration.ofDays(8).toNanos() / 1000,
                                    1)));
            store.flush();
            store.expire();
            assertEquals(4, queries.list(null, null, 100, 0).size());
            new TraceStore(fixture.db, false, 7, clock).accept(List.of(record(run, start, 1)));
            assertEquals(4, fixture.db.list("select * from trace_span").size());
        }
    }

    @Test
    void boundedQueueAndFlushFailureCannotEscape() {
        Db db = mock(Db.class);
        when(db.tx(any())).thenThrow(new IllegalStateException("unavailable"));
        var store = new TraceStore(db, true, 7, clock);
        store.accept(
                Collections.nCopies(5001, record(UUID.randomUUID(), clock.millis() * 1000, 1)));
        assertEquals(5000, store.pending());
        assertEquals(1, store.dropped());
        verifyNoInteractions(db);
        assertDoesNotThrow(store::flush);
        assertEquals(0, store.pending());
    }
}
