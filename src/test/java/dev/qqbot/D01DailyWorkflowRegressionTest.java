package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.qqbot.model.*;
import dev.qqbot.schedule.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class D01DailyWorkflowRegressionTest {
    @TempDir Path directory;

    @Test
    void readsAllMessagesAndKeepsTheLastActionAfterAnInvalidRead() throws Exception {
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("daily.db"))) {
            UUID group = fixture.group("daily-core");
            fixture.db.exec("update group_config set reply_enabled=true where id=?", group);
            var now = Instant.parse("2026-11-15T04:00:00Z");
            var service = new DailySummaryService(fixture.db, Clock.fixed(now, ZoneOffset.UTC));
            for (int i = 0; i < 60; i++) {
                fixture.message(group, "m" + i, "A", "闲聊 " + i, i + 1, now.minusSeconds(86400 - i));
            }
            UUID last =
                    fixture.message(
                            group, "tail", "小王", "待办：小王周四前补齐压测结果", 61, now.minusSeconds(86000));
            UUID id = service.trigger(group, "WEB");
            var inputs = new ArrayList<String>();
            var pi = mock(TextGenerator.class);
            when(pi.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                    .thenAnswer(
                            call -> {
                                GenerationRequest request = call.getArgument(0);
                                GenerationObserver observer = call.getArgument(1);
                                observer.dispatched();
                                inputs.add(request.userPrompt());
                                return new TextGenerator.Result(
                                        inputs.size() == 1
                                                ? "{\"facts\":[],\"dispositions\":[]}"
                                                : DailySummaryTestModel.reply(request.userPrompt()),
                                        null);
                            });
            var worker = new DailySummaryWorker(fixture.db, service, pi);
            try {
                ReflectionTestUtils.invokeMethod(worker, "run", id);
            } finally {
                worker.close();
            }
            var run = fixture.db.one("select * from daily_run where id=?", id);
            assertEquals(
                    "GENERATED",
                    run.get("state"),
                    "new workflow must recover instead of accepting incomplete reads");
            String report = (String) run.get("report");
            assertTrue(report.contains("小王周四前补齐压测结果"));
            assertTrue(report.contains(last.toString()));
            assertEquals(61, ((List<?>) run.get("source_ids")).size());
            assertFalse(
                    fixture.db.list("select id from outbox where daily_run_id=?", id).isEmpty());
            assertTrue(inputs.stream().anyMatch(p -> p.contains("RESPONSE_FORMAT")));
            assertTrue(inputs.size() < 20);
            boolean tailRead = false;
            for (String prompt : inputs) {
                var input = DailySummaryTestModel.input(prompt);
                if (input.path("action").asText().equals("READ_BATCH")) {
                    for (var unit : input.path("units")) {
                        if (unit.path("order").asInt() == 60) {
                            tailRead = true;
                        }
                    }
                } else {
                    assertTrue(tailRead, "selection/writing must wait for the unread tail");
                }
            }
        }
    }
}
