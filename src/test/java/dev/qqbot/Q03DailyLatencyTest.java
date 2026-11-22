package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.qqbot.model.*;
import dev.qqbot.schedule.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Q03DailyLatencyTest {
    @TempDir Path directory;

    @Test
    void adaptiveShortMessageBatchesReadEveryUnitWithinTwelveCalls() {
        for (int count : List.of(219, 312)) {
            var messages = new ArrayList<DailySummaryFacts.SourceMessage>();
            for (int i = 0; i < count; i++) {
                messages.add(Q01DailyEditorialTest.message("闲聊 " + i));
            }
            messages.set(count - 1, Q01DailyEditorialTest.message("待办：小王周四前补齐压测结果"));
            var result =
                    Q01DailyEditorialTest.run(
                            Q01DailyEditorialTest.flow(), messages, Q01DailyEditorialTest::normal);
            assertEquals(count, result.diagnostics().processedUnits());
            assertTrue(result.report().contains("周四"));
            assertTrue(result.diagnostics().calls() <= 12);
            assertTrue(result.diagnostics().maxRequestEstimate() <= 8000);
            assertTrue(
                    result.diagnostics().trace().stream()
                            .noneMatch(s -> s.targetAction().equals("ORGANIZE_TOPIC")));
            assertTrue(result.diagnostics().stageMillis().containsKey("EDIT_MATERIAL"));
        }
    }

    @Test
    void fiveAndTenMinuteWorkloadsUseDailyDeadlineAndStillRespectTotalTimeout() {
        for (int minutes : List.of(5, 10)) {
            var clock = new AtomicLong();
            var flow =
                    new DailySummaryWorkflow(
                            MemoryProperties.defaults(),
                            new DailySummaryWorkflow.Lifecycle(
                                    TimeUnit.MINUTES.toNanos(30), () -> {}, clock::get),
                            step -> {},
                            DailySummaryWorkflow.Audience.defaults());
            var result =
                    Q01DailyEditorialTest.run(
                            flow,
                            List.of(Q01DailyEditorialTest.message("待办：小王周四前补齐压测结果")),
                            in -> {
                                clock.addAndGet(TimeUnit.MINUTES.toNanos(minutes) / 3);
                                return Q01DailyEditorialTest.normal(in);
                            });
            assertEquals(3, result.diagnostics().calls());
            assertEquals(
                    TimeUnit.MINUTES.toMillis(minutes),
                    result.diagnostics().stageMillis().values().stream()
                            .mapToLong(Long::longValue)
                            .sum());
        }
        var clock = new AtomicLong();
        var flow =
                new DailySummaryWorkflow(
                        MemoryProperties.defaults(),
                        new DailySummaryWorkflow.Lifecycle(
                                TimeUnit.MINUTES.toNanos(30), () -> {}, clock::get),
                        step -> {},
                        DailySummaryWorkflow.Audience.defaults());
        assertEquals(
                "SUMMARY_TIMEOUT",
                assertThrows(
                                IllegalStateException.class,
                                () ->
                                        Q01DailyEditorialTest.run(
                                                flow,
                                                List.of(Q01DailyEditorialTest.message("资料")),
                                                in -> {
                                                    clock.set(TimeUnit.MINUTES.toNanos(31));
                                                    return Q01DailyEditorialTest.normal(in);
                                                }))
                        .getMessage());
        assertEquals(0, flow.diagnostics().processedUnits());
    }

    @Test
    void twoGroupsQueueBehindOneWorkerWithoutDuplicateGeneration() throws Exception {
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("queue.db"))) {
            var db = fixture.db;
            var now = Instant.parse("2026-11-15T04:00:00Z");
            var service = new DailySummaryService(db, Clock.fixed(now, ZoneOffset.UTC));
            var runs = new ArrayList<UUID>();
            for (String name : List.of("one", "two")) {
                UUID group = fixture.group(name);
                db.exec("update group_config set reply_enabled=true where id=?", group);
                fixture.message(group, name, "小王", "待办：小王周四前补齐压测结果", 1, now.minusSeconds(86400));
                runs.add(service.trigger(group, "WEB"));
            }
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var calls = Collections.synchronizedList(new ArrayList<UUID>());
            var model = mock(TextGenerator.class);
            when(model.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                    .thenAnswer(
                            call -> {
                                GenerationRequest request = call.getArgument(0);
                                GenerationObserver observer = call.getArgument(1);
                                observer.dispatched();
                                calls.add(request.runId());
                                if (calls.size() == 1) {
                                    entered.countDown();
                                    assertTrue(release.await(5, TimeUnit.SECONDS));
                                }
                                return new TextGenerator.Result(
                                        DailySummaryTestModel.reply(request.userPrompt()), null);
                            });
            var worker = new DailySummaryWorker(db, service, model);
            try {
                worker.tick();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 3; i++) {
                    worker.tick();
                }
                assertEquals(1, db.list("select id from daily_run where state='QUEUED'").size());
                assertEquals(1, calls.size());
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (db.list("select id from daily_run where state='GENERATED'").size() < 2) {
                    worker.tick();
                    if (System.nanoTime() > deadline) {
                        fail("queued daily tasks did not finish");
                    }
                    Thread.sleep(10);
                }
                assertEquals(6, calls.size());
                assertEquals(3, Collections.frequency(calls, runs.get(0)));
                assertEquals(3, Collections.frequency(calls, runs.get(1)));
                assertNotNull(worker.timing());
                assertEquals(2, db.list("select distinct daily_run_id from outbox").size());
            } finally {
                release.countDown();
                worker.close();
            }
        }
    }
}
