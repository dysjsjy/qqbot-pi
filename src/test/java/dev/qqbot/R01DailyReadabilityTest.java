package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.qqbot.admin.ScheduleAdminController;
import dev.qqbot.model.*;
import dev.qqbot.schedule.*;
import dev.qqbot.schedule.DailySummaryFacts.SourceMessage;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class R01DailyReadabilityTest {
    @TempDir Path directory;

    @Test
    void scheduleSettingsPersistStyleAndSpeakerModesAndRemainBackwardCompatible() {
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("schedule-modes.db"))) {
            var db = fixture.db;
            UUID group = fixture.group("modes");
            var now = Instant.parse("2026-11-15T04:00:00Z");
            var summaries = new DailySummaryService(db, Clock.fixed(now, ZoneOffset.UTC));
            var service =
                    new ScheduleSettingsService(db, Clock.fixed(now, ZoneOffset.UTC), summaries);
            var controller = new ScheduleAdminController(service);

            var defaults = controller.get(group);
            assertEquals("CASUAL", defaults.get("style_mode"));
            assertEquals("KEY_ONLY", defaults.get("speaker_mode"));

            var saved =
                    controller.save(
                            group,
                            new ScheduleAdminController.Input(true, "09:30", "WORK", "ANONYMOUS"));
            assertEquals("WORK", saved.get("style_mode"));
            assertEquals("ANONYMOUS", saved.get("speaker_mode"));
            assertEquals(true, saved.get("enabled"));

            // Legacy 2-field input preserves previously saved style_mode and speaker_mode
            var legacyUpdated =
                    controller.save(group, new ScheduleAdminController.Input(false, "10:00"));
            assertEquals("WORK", legacyUpdated.get("style_mode"));
            assertEquals("ANONYMOUS", legacyUpdated.get("speaker_mode"));

            assertEquals(
                    400,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            controller.save(
                                                    group,
                                                    new ScheduleAdminController.Input(
                                                            true,
                                                            "09:00",
                                                            "INVALID_MODE",
                                                            "KEY_ONLY")))
                            .getStatusCode()
                            .value());
        }
    }

    @Test
    void preprocessorResolvesMentionsStripsStickersCondensesCardsAndAutoDisposesMarkupUnits() {
        Map<String, String> members = Map.of("OPENID_ALICE", "小美", "OPENID_BOT", "小肥鱼");
        assertEquals(
                "@小美 今天去自习吗",
                DailySummaryPreprocessor.cleanText(
                        "<@OPENID_ALICE> 今天去自习吗 <faceType=6,faceId=\"0\",ext=\"eyJ0ZXh0IjoiIn0=\">",
                        members));
        assertEquals(
                "",
                DailySummaryPreprocessor.cleanText(
                        "<faceType=6,faceId=\"0\",ext=\"eyJ0ZXh0IjoiIn0=\"> <attachmentType=\"image/jpeg\",attachmentIndex=0,description=\"eyJ0ZXh0IjoiW+WbvueJh10ifQ==\">",
                        members));

        String card =
                "[卡片消息] 图文H5\n摘要: [分享]工科生画四层PCB\ntag: 小红书\ntitle: 工科生画四层PCB\njump_url: https://www.xiaohongshu.com/discovery/item/123?token=secret";
        String cleanedCard = DailySummaryPreprocessor.cleanText(card, members);
        assertTrue(cleanedCard.contains("[分享卡片·小红书]"));
        assertTrue(cleanedCard.contains("工科生画四层PCB"));
        assertFalse(cleanedCard.contains("https://"));

        var messages =
                List.of(
                        new SourceMessage(
                                UUID.randomUUID().toString(),
                                "2026-11-14T10:00:00Z",
                                "小王",
                                "<faceType=6,faceId=\"0\",ext=\"eyJ0ZXh0IjoiIn0=\">"),
                        new SourceMessage(
                                UUID.randomUUID().toString(),
                                "2026-11-14T10:01:00Z",
                                "小王",
                                "<@OPENID_ALICE> 待办：小王周四前补齐压测结果"),
                        new SourceMessage(
                                UUID.randomUUID().toString(),
                                "2026-11-14T10:02:00Z",
                                "小美",
                                "<attachmentType=\"image/jpeg\",attachmentIndex=0,description=\"e30=\">"));
        var seenUnits = new ArrayList<JsonNode>();
        var seenFacts = new ArrayList<JsonNode>();
        var flow =
                new DailySummaryWorkflow(
                        MemoryProperties.defaults(),
                        new DailySummaryWorkflow.Lifecycle(
                                System.nanoTime() + TimeUnit.MINUTES.toNanos(10),
                                () -> {},
                                System::nanoTime),
                        step -> {},
                        new DailySummaryWorkflow.Audience(SummaryProfile.defaults(), members));
        var result =
                flow.execute(
                        messages,
                        prompt -> {
                            try {
                                JsonNode in = DailySummaryTestModel.input(prompt);
                                if (in.path("action").asText().equals("READ_BATCH")) {
                                    in.path("units").forEach(seenUnits::add);
                                }
                                if (in.path("action").asText().equals("EDIT_MATERIAL")) {
                                    in.path("facts").forEach(seenFacts::add);
                                }
                                return DailySummaryTestModel.reply(prompt);
                            } catch (Exception e) {
                                throw new AssertionError(e);
                            }
                        });
        // Only the 1 substantive unit was sent to the LLM, but all 3 units were processed and
        // accounted for!
        assertEquals(1, seenUnits.size());
        assertTrue(seenUnits.get(0).path("text").asText().contains("@小美"));
        assertEquals(3, result.diagnostics().totalUnits());
        assertEquals(3, result.diagnostics().processedUnits());
        assertFalse(seenFacts.isEmpty());
        assertEquals(1, seenFacts.get(0).path("sourceCount").asInt());
        assertEquals(1, seenFacts.get(0).path("speakerCount").asInt());
    }

    @Test
    void anonymousSpeakerModeAllowsOmittingOwnerAndRepairsLeakedSpeakerNames() {
        var messages =
                List.of(
                        new SourceMessage(
                                UUID.randomUUID().toString(),
                                "2026-11-14T12:00:00Z",
                                "小王",
                                "待办：小王周四前补齐压测结果"),
                        new SourceMessage(
                                UUID.randomUUID().toString(),
                                "2026-11-14T12:05:00Z",
                                "澜语404",
                                "新地铁线今天开通了"));
        var flow =
                new DailySummaryWorkflow(
                        MemoryProperties.defaults(),
                        new DailySummaryWorkflow.Lifecycle(
                                System.nanoTime() + TimeUnit.MINUTES.toNanos(10),
                                () -> {},
                                System::nanoTime),
                        step -> {},
                        new DailySummaryWorkflow.Audience(
                                new SummaryProfile(
                                        SummaryProfile.StyleMode.WORK,
                                        SummaryProfile.SpeakerMode.ANONYMOUS),
                                Map.of()));
        AtomicInteger writeAttempts = new AtomicInteger();
        var result =
                flow.execute(
                        messages,
                        prompt -> {
                            try {
                                JsonNode in = DailySummaryTestModel.input(prompt);
                                String action = in.path("action").asText();
                                if (action.equals("WRITE_REPORT")) {
                                    int attempt = writeAttempts.incrementAndGet();
                                    var out = DailySummaryTestModel.JSON.createObjectNode();
                                    out.put(
                                            "lead",
                                            attempt == 1
                                                    ? "小王与群友讨论了压测与地铁。"
                                                    : "群内明确了压测交付时间并交流地铁开通。");
                                    ArrayNode paragraphs = out.putArray("paragraphs");
                                    for (JsonNode fact : in.path("facts")) {
                                        var p = paragraphs.addObject();
                                        String text = fact.path("text").asText();
                                        p.put(
                                                "text",
                                                attempt == 1 ? text : text.replace("小王", "相关成员"));
                                        p.putArray("facts").add(fact.path("id").asText());
                                        var sources = p.putArray("sources");
                                        fact.path("sources").forEach(s -> sources.add(s.asText()));
                                    }
                                    return out.toString();
                                }
                                return DailySummaryTestModel.reply(prompt);
                            } catch (Exception e) {
                                throw new AssertionError(e);
                            }
                        });
        assertEquals(2, writeAttempts.get());
        assertTrue(
                result.diagnostics().trace().stream()
                        .anyMatch(s -> s.feedbackCode().equals("SPEAKER_LEAKED")));
        assertFalse(result.report().contains("小王"));
        assertTrue(result.report().contains("周四"));
        assertTrue(result.report().contains("\n\n"));

        var parts =
                DailySummaryPresentation.render(
                        result.report(), LocalDate.of(2026, 11, 14), Map.of("styleMode", "WORK"));
        String rendered = String.join("", parts);
        assertTrue(rendered.startsWith("昨日协作纪要 · 2026-11-14"));
        assertTrue(rendered.contains("群内明确了压测交付时间并交流地铁开通。"));
    }

    @Test
    void nullCollectionSinceDoesNotFabricateGapAndWorkerRetriesTransientModelFailure()
            throws Exception {
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("retry-and-gap.db"))) {
            var db = fixture.db;
            UUID group = fixture.group("gap-free");
            var now = Instant.parse("2026-11-15T04:00:00Z");
            db.exec(
                    "update group_config set enabled=true,reply_enabled=true,collection_since=null,created_at=? where id=?",
                    now.minus(Duration.ofDays(5)),
                    group);
            fixture.message(group, "m1", "小王", "待办：小王周四前补齐压测结果", 1, now.minusSeconds(86400));

            var service = new DailySummaryService(db, Clock.fixed(now, ZoneOffset.UTC));
            UUID runId = service.trigger(group, "WEB");

            AtomicInteger writeCalls = new AtomicInteger();
            var model = mock(TextGenerator.class);
            when(model.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                    .thenAnswer(
                            inv -> {
                                GenerationRequest req = inv.getArgument(0);
                                GenerationObserver obs = inv.getArgument(1);
                                obs.dispatched();
                                JsonNode in = DailySummaryTestModel.input(req.userPrompt());
                                if (in.path("action").asText().equals("WRITE_REPORT")
                                        && writeCalls.incrementAndGet() == 1) {
                                    throw new IllegalStateException("PI_FAILED");
                                }
                                return new TextGenerator.Result(
                                        DailySummaryTestModel.reply(req.userPrompt()), null);
                            });

            var worker = new DailySummaryWorker(db, service, model);
            try {
                ReflectionTestUtils.invokeMethod(worker, "run", runId);
            } finally {
                worker.close();
            }

            var stored = db.one("select state,report,coverage from daily_run where id=?", runId);
            assertEquals("GENERATED", stored.get("state"));
            @SuppressWarnings("unchecked")
            var coverage = (Map<String, Object>) stored.get("coverage");
            assertTrue(((Collection<?>) coverage.get("gaps")).isEmpty());
            assertEquals("CASUAL", coverage.get("styleMode"));

            String outboxBody =
                    (String)
                            db.one(
                                            "select body from outbox where daily_run_id=? and part_index=0",
                                            runId)
                                    .get("body");
            assertTrue(outboxBody.startsWith("昨日群聊日报 · 2026-11-14"));
            assertFalse(outboxBody.contains("采集有缺口"));
            assertEquals(4, worker.timing().dispatchedCalls());
        }
    }
}
