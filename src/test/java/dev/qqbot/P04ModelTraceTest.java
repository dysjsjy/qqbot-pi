package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.qqbot.model.*;
import dev.qqbot.trace.*;
import dev.qqbot.usage.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class P04ModelTraceTest {
    @Test
    void backgroundTokensAndFailureAreRecordedWithoutChangingExceptions() {
        var delegate = mock(TextGenerator.class);
        var store = mock(ModelCallStore.class);
        var metered = new MeteredTextGenerator(delegate, store);
        List<SpanRecord> spans = new ArrayList<>();
        metered.setTraces(spans::addAll);
        var request =
                new GenerationRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        2,
                        Purpose.DAILY_SUMMARY,
                        "system",
                        "question",
                        8000,
                        800,
                        30);
        var usage = new ModelUsage(100L, 10L, 20L, 0L, 5L, UsageStatus.REPORTED);
        when(delegate.generate(any(), any()))
                .thenAnswer(
                        invocation -> {
                            GenerationObserver observer = invocation.getArgument(1);
                            observer.dispatched();
                            observer.usage(usage);
                            return new TextGenerator.Result("answer", null, usage);
                        });
        assertEquals("answer", metered.generate(request, GenerationObserver.noop()).text());
        assertFalse(Trace.active());
        assertEquals(2, spans.size());
        assertEquals("background.daily_summary", spans.get(0).name());
        assertEquals(100L, spans.get(1).inputTokens());
        assertEquals(270L, spans.get(1).estimatedInputTokens());
        assertEquals(spans.get(0).id().toString(), spans.get(1).parentSpan());
        assertEquals(100L, spans.get(0).inputTokens());
        var failure = new IllegalStateException("MODEL_FAILED");
        doThrow(failure).when(delegate).generate(any(), any());
        spans.clear();
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> metered.generate(request, GenerationObserver.noop())));
        assertEquals("ERROR", spans.get(1).status());
        assertEquals("MODEL_FAILED", spans.get(1).errorCode());
        assertFalse(Trace.active());
    }
}
