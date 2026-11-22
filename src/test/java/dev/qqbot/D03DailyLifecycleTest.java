package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.qqbot.model.*;
import dev.qqbot.schedule.*;
import dev.qqbot.schedule.DailySummaryFacts.SourceMessage;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class D03DailyLifecycleTest {
    @TempDir Path directory;

    @Test
    void wholeWorkflowSharesTheCallAndTimeBudgets() {
        var messages = new ArrayList<SourceMessage>();
        for (int i = 0; i < 1000; i++) {
            messages.add(
                    new SourceMessage(UUID.randomUUID().toString(), "time", "A", "x".repeat(600)));
        }
        // Stay inside the input protection; simulate the shared budget nearly exhausted by prior
        // actions.
        messages.replaceAll(
                m -> new SourceMessage(m.messageId(), m.time(), m.sender(), "x".repeat(100)));
        var flow =
                new DailySummaryWorkflow(
                        MemoryProperties.defaults(), Long.MAX_VALUE, () -> {}, s -> {});
        ReflectionTestUtils.setField(flow, "calls", 39);
        assertEquals(
                "CALL_LIMIT",
                assertThrows(
                                IllegalStateException.class,
                                () ->
                                        flow.execute(
                                                messages,
                                                prompt -> {
                                                    try {
                                                        return DailySummaryTestModel.reply(prompt);
                                                    } catch (Exception failure) {
                                                        throw new AssertionError(failure);
                                                    }
                                                }))
                        .getMessage());
        assertEquals(40, flow.diagnostics().calls());
        assertTrue(flow.diagnostics().processedUnits() < 1000);
        var clock = new AtomicLong(0);
        var timeout =
                new DailySummaryWorkflow(
                        MemoryProperties.defaults(),
                        new DailySummaryWorkflow.Lifecycle(100, () -> {}, clock::get),
                        step -> {},
                        DailySummaryWorkflow.Audience.defaults());
        assertEquals(
                "SUMMARY_TIMEOUT",
                assertThrows(
                                IllegalStateException.class,
                                () ->
                                        timeout.execute(
                                                messages.subList(0, 1),
                                                prompt -> {
                                                    clock.set(101);
                                                    return "{}";
                                                }))
                        .getMessage());
        assertEquals(0, timeout.diagnostics().processedUnits());
        assertEquals(1, timeout.diagnostics().calls());
        var localLimit =
                new DailySummaryWorkflow(
                        MemoryProperties.defaults(), Long.MAX_VALUE, () -> {}, s -> {});
        ReflectionTestUtils.setField(localLimit, "steps", 120);
        assertEquals(
                "SUMMARY_NO_PROGRESS",
                assertThrows(
                                IllegalStateException.class,
                                () ->
                                        localLimit.execute(
                                                messages.subList(0, 1),
                                                prompt -> {
                                                    throw new AssertionError(
                                                            "local limit must stop before model entry");
                                                }))
                        .getMessage());
        assertEquals(0, localLimit.diagnostics().calls());
    }

    @Test
    void epochChangeAtAnySemanticPhaseAndUntrustedExceptionCannotPublish() {
        var now = Instant.parse("2026-11-15T04:00:00Z");
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("lifecycle.db"))) {
            for (String phase :
                    List.of(
                            "READ_BATCH",
                            "EDIT_MATERIAL",
                            "WRITE_REPORT",
                            "EXCEPTION",
                            "INVALID")) {
                UUID group = fixture.group("phase-" + phase);
                fixture.db.exec("update group_config set reply_enabled=true where id=?", group);
                for (int i = 0; i < 25; i++) {
                    fixture.message(
                            group, phase + i, "A", "资料", i + 1, now.minusSeconds(86400 - i));
                }
                var service = new DailySummaryService(fixture.db, Clock.fixed(now, ZoneOffset.UTC));
                UUID id = service.trigger(group, "WEB");
                var model = mock(TextGenerator.class);
                when(model.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                        .thenAnswer(
                                invocation -> {
                                    GenerationRequest request = invocation.getArgument(0);
                                    GenerationObserver observer = invocation.getArgument(1);
                                    observer.dispatched();
                                    if (phase.equals("EXCEPTION")) {
                                        throw new IllegalStateException(
                                                "private-message-must-not-be-an-error-code");
                                    }
                                    if (phase.equals("INVALID")) {
                                        return new TextGenerator.Result("{}", null);
                                    }
                                    if (DailySummaryTestModel.input(request.userPrompt())
                                            .path("action")
                                            .asText()
                                            .equals(phase)) {
                                        fixture.db.exec(
                                                "update group_config set epoch=epoch+1 where id=?",
                                                group);
                                    }
                                    return new TextGenerator.Result(
                                            DailySummaryTestModel.reply(request.userPrompt()),
                                            null);
                                });
                var worker = new DailySummaryWorker(fixture.db, service, model);
                try {
                    ReflectionTestUtils.invokeMethod(worker, "run", id);
                } finally {
                    worker.close();
                }
                var row =
                        fixture.db.one(
                                "select state,error_code,report from daily_run where id=?", id);
                assertEquals(
                        phase.equals("EXCEPTION") || phase.equals("INVALID")
                                ? "FAILED"
                                : "CANCELLED",
                        row.get("state"));
                if (phase.equals("EXCEPTION")) {
                    assertEquals("SUMMARY_FAILED", row.get("error_code"));
                }
                if (phase.equals("INVALID")) {
                    assertEquals("SUMMARY_NO_PROGRESS", row.get("error_code"));
                }
                assertNull(row.get("report"));
                assertTrue(
                        fixture.db
                                .list("select id from outbox where daily_run_id=?", id)
                                .isEmpty());
            }
        }
    }
}
