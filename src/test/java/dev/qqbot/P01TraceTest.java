package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import dev.qqbot.trace.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class P01TraceTest {
    @AfterEach
    void cleanup() {
        Trace.end(null, null);
    }

    @Test
    void treeLateIdentityAndTokens() {
        List<List<SpanRecord>> batches = new ArrayList<>();
        UUID run = UUID.randomUUID(), group = UUID.randomUUID();
        Trace.begin(batches::add, Trace.INGRESS, "root", null, null);
        Trace.identify(run, group);
        try (var outer = Trace.span("outer")) {
            try (var model = Trace.span("model.call")) {
                model.tokens(10L, 1L, 2L, 3L, null, null);
            }
        }
        Trace.end(Trace.OK, null);
        assertFalse(Trace.active());
        var spans = batches.get(0);
        assertEquals(
                List.of("root", "outer", "model.call"),
                spans.stream().map(SpanRecord::name).toList());
        assertNull(spans.get(0).parentSpan());
        assertEquals(spans.get(1).id().toString(), spans.get(2).parentSpan());
        assertEquals(10L, spans.get(0).estimatedInputTokens());
        assertEquals(3L, spans.get(0).cacheReadTokens());
        assertNull(spans.get(0).cacheWriteTokens());
        for (var span : spans) {
            assertEquals(run, span.runId());
            assertEquals(group, span.groupId());
            assertTrue(span.durationUs() >= 0);
        }
    }

    @Test
    void noopLimitsAndFailureIsolation() throws Exception {
        Trace.begin(null, Trace.AGENT, "root", null, null);
        assertFalse(Trace.active());
        Trace.span("noop").close();
        List<List<SpanRecord>> batches = new ArrayList<>();
        Trace.begin(batches::add, Trace.AGENT, "root", UUID.randomUUID(), null);
        var isolated = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread worker = new Thread(() -> isolated.set(Trace.active()));
        worker.start();
        worker.join();
        assertFalse(isolated.get());
        for (int index = 0; index < 250; index++) {
            Trace.span("child").close();
        }
        Trace.end(Trace.CANCELLED, "STALE");
        assertEquals(200, batches.get(0).size());
        assertEquals(51, batches.get(0).get(0).attributes().get("droppedSpans"));
        assertEquals(Trace.CANCELLED, batches.get(0).get(0).status());
        Trace.begin(
                records -> {
                    throw new IllegalStateException();
                },
                Trace.AGENT,
                "root",
                UUID.randomUUID(),
                null);
        assertDoesNotThrow(() -> Trace.end(Trace.OK, null));
        assertFalse(Trace.active());
    }
}
