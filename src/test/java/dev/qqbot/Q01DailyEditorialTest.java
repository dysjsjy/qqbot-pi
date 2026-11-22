/**
 * [INPUT]: 使用分阶段模型桩、日报输入与场景配置。
 * [OUTPUT]: 验证必留事实、编辑选材、格式修复与过期选材拒绝。
 * [POS]: 日报语义约束与流程恢复的回归测试。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.qqbot.schedule.*;
import dev.qqbot.schedule.DailySummaryFacts.SourceMessage;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class Q01DailyEditorialTest {
    static SourceMessage message(String text) {
        return new SourceMessage(UUID.randomUUID().toString(), "2026-11-14T12:00:00Z", "小王", text);
    }

    static String normal(JsonNode in) {
        try {
            return DailySummaryTestModel.reply("\nINPUT_JSON\n" + in);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static DailySummaryWorkflow flow() {
        return new DailySummaryWorkflow(
                MemoryProperties.defaults(),
                System.nanoTime() + TimeUnit.MINUTES.toNanos(30),
                () -> {},
                s -> {});
    }

    static DailySummaryWorkflow.Result run(
            DailySummaryWorkflow flow,
            List<SourceMessage> messages,
            Function<JsonNode, String> model) {
        return flow.execute(
                messages,
                p -> {
                    try {
                        return model.apply(DailySummaryTestModel.input(p));
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                });
    }

    static ObjectNode parse(String text) {
        try {
            return (ObjectNode) DailySummaryTestModel.JSON.readTree(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void typeDoesNotForceTrivialActionsQuestionsOrJokesIntoTheDigest() {
        var messages =
                List.of(
                        message("我要睡了"),
                        message("这是什么"),
                        message("玩笑争论"),
                        message("待办：小王周四前补齐压测结果"));
        var result =
                run(
                        flow(),
                        messages,
                        in -> {
                            if (in.path("action").asText().equals("READ_BATCH")) {
                                var out = DailySummaryTestModel.JSON.createObjectNode();
                                var facts = out.putArray("facts");
                                var ignored = out.putObject("ignored");
                                ignored.putArray("CHAT");
                                String[] kinds = {"ACTION", "OPEN_QUESTION", "DISPUTE", "ACTION"};
                                for (int i = 0; i < 4; i++) {
                                    var f =
                                            facts.addObject()
                                                    .put("kind", kinds[i])
                                                    .put("text", messages.get(i).text())
                                                    .put("topic", "主题" + i)
                                                    .put("owner", i == 3 ? "小王" : "")
                                                    .put("due", i == 3 ? "周四" : "");
                                    f.put("value", i == 3 ? "REQUIRED" : "OPTIONAL")
                                            .put("reason", i == 3 ? "DEADLINE" : "USEFUL")
                                            .putArray("units")
                                            .add(in.path("units").get(i).path("id").asText());
                                }
                                return out.toString();
                            }
                            var out = parse(normal(in));
                            if (in.path("action").asText().equals("EDIT_MATERIAL")) {
                                for (var f : out.path("facts")) {
                                    if (!f.path("value").asText().equals("REQUIRED")) {
                                        ((ObjectNode) f)
                                                .put("value", "OMIT")
                                                .put("reason", "LOW_VALUE")
                                                .put("detail", "");
                                    }
                                }
                            }
                            return out.toString();
                        });
        assertEquals(4, result.diagnostics().processedUnits());
        assertEquals(1, result.diagnostics().selectedCount());
        assertEquals(3, result.diagnostics().omissionReasons().get("LOW_VALUE"));
        assertEquals(1, result.diagnostics().criticalCovered());
        assertTrue(result.report().contains("周四"));
        assertFalse(result.report().contains("睡"));
        assertFalse(result.report().contains("争论"));
    }

    @Test
    void invalidReadIsAtomicAndUrlDraftIsRepairedWithoutReReading() {
        var flow = flow();
        int[] reads = {0}, writes = {0};
        var result =
                run(
                        flow,
                        List.of(message("待办：小王周四前补齐压测结果")),
                        in -> {
                            String action = in.path("action").asText();
                            if (action.equals("READ_BATCH") && reads[0]++ == 0) {
                                return "{\"facts\":[],\"ignored\":{\"CHAT\":[]}}";
                            }
                            var out = parse(normal(in));
                            if (action.equals("WRITE_REPORT")) {
                                int attempt = writes[0]++;
                                if (attempt == 0) {
                                    ((ObjectNode) out.path("paragraphs").get(0))
                                            .put("text", "查看分享：https://example.com");
                                }
                                if (attempt == 1) {
                                    ((ObjectNode) out.path("paragraphs").get(0))
                                            .put("text", "补齐压测结果");
                                }
                            }
                            return out.toString();
                        });
        assertEquals(2, reads[0]);
        assertEquals(3, writes[0]);
        assertTrue(
                result.diagnostics().trace().stream()
                        .anyMatch(
                                s ->
                                        s.feedbackCode().equals("RESPONSE_FORMAT")
                                                && s.processedUnits() == 0));
        assertTrue(
                result.diagnostics().trace().stream()
                        .anyMatch(s -> s.feedbackCode().equals("REPORT_PRESENTATION_INVALID")));
        assertTrue(result.report().contains("周四"));
        assertFalse(result.report().contains("https"));
        Object material = ReflectionTestUtils.getField(flow, "material");
        int version = (int) ReflectionTestUtils.getField(material, "factsVersion");
        ReflectionTestUtils.setField(material, "factsVersion", version + 1);
        assertEquals(
                "SUMMARY_WORKFLOW_INVARIANT",
                assertThrows(
                                IllegalStateException.class,
                                () -> ReflectionTestUtils.invokeMethod(flow, "complete"))
                        .getMessage());
    }

    @Test
    void importantFactCannotBeSilentlyDemotedOrDisappearDuringEditing() {
        var flow = flow();
        assertEquals(
                "SUMMARY_REQUIRED_FACT_MISSING",
                assertThrows(
                                IllegalStateException.class,
                                () ->
                                        run(
                                                flow,
                                                List.of(message("待办：小王周四前补齐压测结果")),
                                                in -> {
                                                    var out = parse(normal(in));
                                                    if (in.path("action")
                                                            .asText()
                                                            .equals("EDIT_MATERIAL")) {
                                                        ((ObjectNode) out.path("facts").get(0))
                                                                .put("value", "OMIT")
                                                                .put("reason", "LOW_VALUE")
                                                                .put("detail", "");
                                                    }
                                                    return out.toString();
                                                }))
                        .getMessage());
        assertEquals(1, flow.diagnostics().processedUnits());
        assertEquals(0, flow.diagnostics().criticalCovered());
    }

    @Test
    void sourcedCorrectionAndRealDisputeAreKeptInOneGlobalOutline() {
        var messages = List.of(message("原定周五上线"), message("周五上线已取消，等待压测结果"), message("兼容问题仍未解决"));
        var result =
                run(
                        flow(),
                        messages,
                        in -> {
                            if (in.path("action").asText().equals("READ_BATCH")) {
                                var out = DailySummaryTestModel.JSON.createObjectNode();
                                var facts = out.putArray("facts");
                                out.putObject("ignored");
                                for (int i = 0; i < 3; i++) {
                                    var f =
                                            facts.addObject()
                                                    .put("kind", i == 2 ? "DISPUTE" : "CONCLUSION")
                                                    .put("text", messages.get(i).text())
                                                    .put("topic", "发布")
                                                    .put("owner", "")
                                                    .put("due", "")
                                                    .put("value", "REQUIRED")
                                                    .put(
                                                            "reason",
                                                            i == 2 ? "UNRESOLVED" : "DECISION");
                                    f.putArray("units")
                                            .add(in.path("units").get(i).path("id").asText());
                                }
                                return out.toString();
                            }
                            if (in.path("action").asText().equals("EDIT_MATERIAL")) {
                                var out = parse(normal(in));
                                var facts = (ArrayNode) out.path("facts");
                                facts.remove(0);
                                ((ObjectNode) facts.get(0))
                                        .put("relation", "CORRECT")
                                        .withArray("inputs")
                                        .add(in.path("facts").get(0).path("id").asText());
                                return out.toString();
                            }
                            return normal(in);
                        });
        assertTrue(result.report().contains("已取消"));
        assertTrue(result.report().contains("仍未解决"));
        assertFalse(result.report().contains("原定"));
        for (var m : messages) {
            assertTrue(result.report().contains(m.messageId()));
        }
        assertEquals(2, result.diagnostics().criticalCovered());
    }

    @Test
    void mergingOptionalDiscussionCanDropSpeakerLabelButRequiredDeadlineCannotDisappear() {
        for (boolean required : List.of(false, true)) {
            var flow = flow();
            var messages = List.of(message("甲的讨论"), message("乙的讨论"));
            java.util.function.Function<JsonNode, String> model =
                    in -> {
                        if (in.path("action").asText().equals("READ_BATCH")) {
                            var out = DailySummaryTestModel.JSON.createObjectNode();
                            var facts = out.putArray("facts");
                            out.putObject("ignored");
                            for (int i = 0; i < 2; i++) {
                                facts.addObject()
                                        .put("kind", required ? "ACTION" : "OPEN_QUESTION")
                                        .put("text", required ? "周四完成" : "极值条件讨论")
                                        .put("topic", "讨论")
                                        .put("owner", i == 0 ? "小王" : "")
                                        .put("due", required ? "周四" : "")
                                        .put("value", required ? "REQUIRED" : "OPTIONAL")
                                        .put("reason", required ? "DEADLINE" : "USEFUL")
                                        .putArray("units")
                                        .add(in.path("units").get(i).path("id").asText());
                            }
                            if (required) {
                                for (var f : facts) {
                                    ((ObjectNode) f).put("owner", "");
                                }
                            }
                            return out.toString();
                        }
                        if (in.path("action").asText().equals("EDIT_MATERIAL")) {
                            var out = DailySummaryTestModel.JSON.createObjectNode();
                            var f =
                                    out.putArray("facts")
                                            .addObject()
                                            .put("kind", required ? "ACTION" : "TOPIC")
                                            .put("text", "讨论已归纳")
                                            .put("topic", "讨论")
                                            .put("owner", "")
                                            .put("due", "")
                                            .put("relation", "MERGE")
                                            .put("value", required ? "REQUIRED" : "OPTIONAL")
                                            .put("reason", required ? "DEADLINE" : "USEFUL")
                                            .put("detail", "BRIEF");
                            var ids = f.putArray("inputs");
                            in.path("facts").forEach(x -> ids.add(x.path("id").asText()));
                            return out.toString();
                        }
                        return normal(in);
                    };
            if (required) {
                // The input must itself carry the literal due string; then losing it in a merge is
                // rejected.
                assertEquals(
                        "SUMMARY_REQUIRED_FACT_MISSING",
                        assertThrows(
                                        IllegalStateException.class,
                                        () ->
                                                run(
                                                        flow,
                                                        List.of(message("周四完成"), message("周四完成")),
                                                        model))
                                .getMessage());
            } else {
                assertTrue(run(flow, messages, model).report().contains("讨论已归纳"));
            }
        }
    }
}
