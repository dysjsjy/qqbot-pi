package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.qqbot.model.*;
import dev.qqbot.usage.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class P08UsageThrottleTest {
    @Test
    void cumulativeEventsAreThrottledAndFlushedBeforeFinish() {
        var store = mock(ModelCallStore.class);
        var delegate = mock(TextGenerator.class);
        var observer = mock(GenerationObserver.class);
        var latest = new ModelUsage(20L, 20L, 0L, 0L, 0L, UsageStatus.REPORTED);
        when(delegate.generate(any(), any()))
                .thenAnswer(
                        invocation -> {
                            GenerationObserver accounting = invocation.getArgument(1);
                            for (long index = 1; index <= 20; index++) {
                                accounting.usage(
                                        new ModelUsage(
                                                index, index, 0L, 0L, 0L, UsageStatus.REPORTED));
                            }
                            return new TextGenerator.Result("answer", null, ModelUsage.unknown());
                        });
        var request =
                new GenerationRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        2,
                        Purpose.CHAT,
                        "system",
                        "question",
                        8000,
                        800,
                        30);
        var metered = new MeteredTextGenerator(delegate, store);
        assertEquals(latest, metered.generate(request, observer).usage());
        verify(store, times(2)).usage(eq(request.callId()), any());
        verify(observer, times(20)).usage(any());
        var first = new ModelUsage(1L, 1L, 0L, 0L, 0L, UsageStatus.REPORTED);
        var ordered = inOrder(store);
        ordered.verify(store).begin(request);
        ordered.verify(store).usage(request.callId(), first);
        ordered.verify(store).usage(request.callId(), latest);
        ordered.verify(store).finish(request.callId(), "SUCCEEDED", null);
        clearInvocations(store);
        doAnswer(
                        invocation -> {
                            GenerationObserver accounting = invocation.getArgument(1);
                            accounting.usage(
                                    new ModelUsage(1L, 1L, 0L, 0L, 0L, UsageStatus.REPORTED));
                            accounting.usage(latest);
                            throw new IllegalStateException("MODEL_FAILED");
                        })
                .when(delegate)
                .generate(any(), any());
        assertThrows(
                IllegalStateException.class,
                () -> metered.generate(request, GenerationObserver.noop()));
        var failed = inOrder(store);
        failed.verify(store).begin(request);
        failed.verify(store).usage(request.callId(), first);
        failed.verify(store).usage(request.callId(), latest);
        failed.verify(store).finish(request.callId(), "FAILED", "MODEL_FAILED");
    }
}
