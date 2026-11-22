/**
 * [INPUT]: 使用冻结消息和可编排的日报模型桩。
 * [OUTPUT]: 验证分批、覆盖率、恢复和过期草稿拦截。
 * [POS]: 日报流程测试；检查调度轨迹与内容状态的衔接。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.qqbot.model.*;
import dev.qqbot.schedule.*;
import dev.qqbot.schedule.DailySummaryFacts.SourceMessage;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class D02DailyWorkflowTest {
    private final List<JsonNode> requests = new ArrayList<>();

    private DailySummaryWorkflow workflow() {
        return new DailySummaryWorkflow(
                MemoryProperties.defaults(),
                System.nanoTime() + TimeUnit.MINUTES.toNanos(30),
                () -> {},
                step -> {});
    }

    private static SourceMessage message(String text) {
        return new SourceMessage(UUID.randomUUID().toString(), "2026-11-14T12:00:00Z", "小王", text);
    }

    private DailySummaryWorkflow.Result run(
            DailySummaryWorkflow workflow,
            List<SourceMessage> messages,
            Function<JsonNode, String> response) {
        return workflow.execute(
                messages,
                prompt -> {
                    try {
                        var in = DailySummaryTestModel.input(prompt);
                        requests.add(in);
                        String raw =
                                response == null
                                        ? DailySummaryTestModel.reply(prompt)
                                        : response.apply(in);
                        if (action(in, "EDIT_MATERIAL") && raw.startsWith("{")) {
                            var out = (ObjectNode) DailySummaryTestModel.JSON.readTree(raw);
                            for (var fact : out.path("facts")) {
                                if (!fact.has("value")) {
                                    boolean required = false;
                                    for (var input : in.path("facts")) {
                                        for (var ref : fact.path("inputs")) {
                                            if (ref.asText().equals(input.path("id").asText())
                                                    && input.path("value")
                                                            .asText()
                                                            .equals("REQUIRED")) {
                                                required = true;
                                            }
                                        }
                                    }
                                    ((ObjectNode) fact)
                                            .put("value", required ? "REQUIRED" : "OPTIONAL")
                                            .put("reason", required ? "DECISION" : "USEFUL")
                                            .put("review", "")
                                            .put("detail", "FULL");
                                }
                            }
                            raw = out.toString();
                        }
                        return raw;
                    } catch (RuntimeException failure) {
                        throw failure;
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
    }

    private static String normal(JsonNode in) {
        try {
            return DailySummaryTestModel.reply("\nINPUT_JSON\n" + in);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static boolean action(JsonNode in, String action) {
        return in.path("action").asText().equals(action);
    }

    @Test
    void oversizedReadIsRejectedAtomicallyThenSplitWithoutReplayingCompletedUnits() {
        var messages = new ArrayList<SourceMessage>();
        for (int i = 0; i < 120; i++) {
            messages.add(message("结论：采用方案甲 " + i));
        }
        int[] firstBatch = {0};
        var flow = workflow();
        var result =
                run(
                        flow,
                        messages,
                        in -> {
                            if (action(in, "READ_BATCH")
                                    && in.path("units").get(0).path("order").asInt() > 0
                                    && in.path("units").size() > 12) {
                                return "x".repeat(4001);
                            }
                            if (action(in, "READ_BATCH")) {
                                if (in.path("units").get(0).path("order").asInt() == 0) {
                                    firstBatch[0] = in.path("units").size();
                                }
                                try {
                                    var out =
                                            (ObjectNode)
                                                    DailySummaryTestModel.JSON.readTree(normal(in));
                                    ((ObjectNode) out.path("facts").get(0))
                                            .put("kind", "CONCLUSION")
                                            .put("text", "采用方案甲")
                                            .put("value", "REQUIRED")
                                            .put("reason", "DECISION");
                                    return out.toString();
                                } catch (Exception failure) {
                                    throw new AssertionError(failure);
                                }
                            }
                            if (action(in, "EDIT_MATERIAL")) {
                                var out = DailySummaryTestModel.JSON.createObjectNode();
                                var fact =
                                        out.putArray("facts")
                                                .addObject()
                                                .put("kind", "CONCLUSION")
                                                .put("text", "采用方案甲")
                                                .put("topic", "发布")
                                                .put("owner", "")
                                                .put("due", "")
                                                .put("relation", "MERGE");
                                var inputs = fact.putArray("inputs");
                                in.path("facts").forEach(f -> inputs.add(f.path("id").asText()));
                                return out.toString();
                            }
                            return normal(in);
                        });
        var stats = result.diagnostics();
        assertEquals(120, stats.processedUnits());
        assertEquals(120, stats.totalUnits());
        assertTrue(stats.replans() > 0);
        assertEquals(
                1,
                requests.stream()
                        .filter(
                                in ->
                                        action(in, "READ_BATCH")
                                                && in.path("units").get(0).path("order").asInt()
                                                        == 0)
                        .count());
        assertTrue(
                stats.trace().stream()
                        .anyMatch(
                                step ->
                                        step.feedbackCode().equals("RESULT_TOO_LARGE")
                                                && step.processedUnits() == firstBatch[0]));
        assertTrue(
                stats.trace().stream()
                        .anyMatch(
                                step ->
                                        step.strategy().equals("SMALLER_BATCH")
                                                && step.progress()));
        assertTrue(stats.maxRequestEstimate() <= 8000);
        assertTrue(stats.calls() <= 40);
        assertEquals(1, stats.factCount());
        assertEquals(1, stats.criticalCovered());
        assertEquals(1, result.report().split("采用方案甲", -1).length - 1);
        var active = activeFacts(flow);
        var merged = (DailySummaryFacts.Fact) active.values().iterator().next();
        assertEquals(120, merged.sources().size());
        evidence("duplicate-conclusion", result);
    }

    @Test
    void cancellationOfADecisionAndUnresolvedDisagreementSurviveOrganization() {
        var messages =
                List.of(message("上午决定周五上线"), message("下午取消周五上线，等待压测结果"), message("兼容问题仍未解决"));
        var result =
                run(
                        workflow(),
                        messages,
                        in -> {
                            if (action(in, "READ_BATCH")) {
                                var out = DailySummaryTestModel.JSON.createObjectNode();
                                var facts = out.putArray("facts");
                                var dispositions = out.putArray("dispositions");
                                String[] texts = {"原定周五上线", "周五上线已取消，等待压测结果", "兼容问题仍未解决"};
                                for (int i = 0; i < 3; i++) {
                                    var fact =
                                            facts.addObject()
                                                    .put("kind", i == 2 ? "DISPUTE" : "CONCLUSION")
                                                    .put("text", texts[i])
                                                    .put("topic", "发布")
                                                    .put("owner", "")
                                                    .put("due", "")
                                                    .put("value", "REQUIRED")
                                                    .put("reason", "DECISION");
                                    String id = in.path("units").get(i).path("id").asText();
                                    fact.putArray("units").add(id);
                                    dispositions
                                            .addObject()
                                            .put("unit", id)
                                            .putArray("facts")
                                            .add(i);
                                }
                                return out.toString();
                            }
                            if (action(in, "EDIT_MATERIAL")) {
                                var out = DailySummaryTestModel.JSON.createObjectNode();
                                var facts = out.putArray("facts");
                                var correction =
                                        facts.addObject()
                                                .put("relation", "CORRECT")
                                                .put("kind", "CONCLUSION")
                                                .put("text", "周五上线已取消，等待压测结果")
                                                .put("topic", "发布")
                                                .put("owner", "")
                                                .put("due", "");
                                correction
                                        .putArray("inputs")
                                        .add(in.path("facts").get(0).path("id").asText())
                                        .add(in.path("facts").get(1).path("id").asText());
                                var dispute =
                                        facts.addObject()
                                                .put("relation", "KEEP")
                                                .put("kind", "DISPUTE")
                                                .put("text", "兼容问题仍未解决")
                                                .put("topic", "发布")
                                                .put("owner", "")
                                                .put("due", "");
                                dispute.putArray("inputs")
                                        .add(in.path("facts").get(2).path("id").asText());
                                return out.toString();
                            }
                            return normal(in);
                        });
        assertTrue(result.report().contains("周五上线已取消"));
        assertTrue(result.report().contains("兼容问题仍未解决"));
        assertFalse(result.report().contains("原定周五上线"));
        assertEquals(2, result.diagnostics().criticalCount());
        assertEquals(2, result.diagnostics().criticalCovered());
        assertEquals(2, result.diagnostics().revisionCount());
        assertTrue(result.report().contains(messages.get(0).messageId()));
        assertTrue(result.report().contains(messages.get(1).messageId()));
        assertTrue(result.report().contains(messages.get(2).messageId()));
        evidence("decision-correction", result);
    }

    @Test
    void completeFactsAndMappedDraftsAllowLargeMaterialToBeGloballyEdited() {
        var messages = new ArrayList<SourceMessage>();
        for (int i = 0; i < 5; i++) {
            messages.add(message("议题 " + i));
        }
        var result =
                run(
                        workflow(),
                        messages,
                        in -> {
                            if (action(in, "READ_BATCH")) {
                                var out = DailySummaryTestModel.JSON.createObjectNode();
                                var facts = out.putArray("facts");
                                var dispositions = out.putArray("dispositions");
                                int i = 0;
                                for (var unit : in.path("units")) {
                                    var fact =
                                            facts.addObject()
                                                    .put("kind", "TOPIC")
                                                    .put("text", "议题讨论背景".repeat(80))
                                                    .put("topic", "话题" + i)
                                                    .put("owner", "")
                                                    .put("due", "");
                                    fact.putArray("units").add(unit.path("id").asText());
                                    dispositions
                                            .addObject()
                                            .put("unit", unit.path("id").asText())
                                            .putArray("facts")
                                            .add(i++);
                                }
                                return out.toString();
                            }
                            if (action(in, "SELECT_FACTS")) {
                                for (var fact : in.path("facts")) {
                                    assertEquals("议题讨论背景".repeat(80), fact.path("text").asText());
                                }
                            }
                            if (action(in, "WRITE_REPORT")
                                    && in.path("strategy").asText().equals("BRIEF")) {
                                var out = DailySummaryTestModel.JSON.createObjectNode();
                                var paragraphs = out.putArray("paragraphs");
                                for (var fact : in.path("facts")) {
                                    var p = paragraphs.addObject().put("text", "议题讨论背景");
                                    p.putArray("facts").add(fact.path("id").asText());
                                    p.putArray("sources").add(fact.path("sources").get(0).asText());
                                }
                                return out.toString();
                            }
                            return normal(in);
                        });
        assertTrue(
                result.diagnostics().trace().stream()
                        .anyMatch(s -> s.feedbackCode().equals("REQUEST_TOO_LARGE")));
        assertEquals(5, result.diagnostics().selectedCount());
        assertTrue(result.diagnostics().maxRequestEstimate() <= 8000);
        assertTrue(result.report().codePointCount(0, result.report().length()) < 1200);
        assertTrue(
                result.diagnostics().trace().stream()
                        .anyMatch(s -> s.changeReason().equals("RESELECT_FACTS")));
        evidence("compact-index", result);
    }

    @Test
    void missingActionAndLongReportGiveSpecificFeedbackAndEffectiveRewrite() {
        int[] failures = {0};
        var result =
                run(
                        workflow(),
                        List.of(message("待办：小王周四前补齐压测结果")),
                        in -> {
                            if (action(in, "WRITE_REPORT") && failures[0]++ < 2) {
                                return "{\"paragraphs\":[]}";
                            }
                            return normal(in);
                        });
        assertTrue(result.report().contains("小王周四前补齐压测结果"));
        assertTrue(
                result.diagnostics().trace().stream()
                        .anyMatch(s -> s.strategy().equals("TARGETED_REWRITE") && s.progress()));
        assertEquals(1, result.diagnostics().criticalCovered());
        assertTrue(
                requests.stream()
                        .anyMatch(
                                in ->
                                        in.path("feedback")
                                                        .path("code")
                                                        .asText()
                                                        .equals("REQUIRED_FACT_MISSING")
                                                && in.path("feedback").path("objects").size()
                                                        == 1));
        requests.clear();
        int[] longDrafts = {0};
        var shortened =
                run(
                        workflow(),
                        List.of(message("待办：小王周四前补齐压测结果")),
                        in -> {
                            String raw = normal(in);
                            if (action(in, "WRITE_REPORT") && longDrafts[0]++ < 2) {
                                try {
                                    var node =
                                            (ObjectNode) DailySummaryTestModel.JSON.readTree(raw);
                                    ((ObjectNode) node.path("paragraphs").get(0))
                                            .put("text", "小王周四前补齐压测结果。" + "长".repeat(1800));
                                    return node.toString();
                                } catch (Exception failure) {
                                    throw new AssertionError(failure);
                                }
                            }
                            return raw;
                        });
        assertTrue(shortened.report().contains("小王周四前补齐压测结果"));
        assertTrue(
                shortened.diagnostics().trace().stream()
                        .anyMatch(s -> s.feedbackCode().equals("REPORT_TOO_LONG")));
        assertTrue(
                shortened.diagnostics().trace().stream()
                        .anyMatch(s -> s.strategy().equals("BRIEF") && s.progress()));
        assertTrue(
                shortened.diagnostics().trace().stream()
                        .anyMatch(s -> s.changeReason().equals("RESELECT_FACTS")));
        evidence("report-reselection", shortened);
    }

    @Test
    void invalidResultsStopWithNoProgressAndUnknownSourcesAreHardFailures() {
        var flow = workflow();
        assertEquals(
                "SUMMARY_RESULT_INVALID",
                assertThrows(
                                IllegalStateException.class,
                                () -> run(flow, List.of(message("资料")), in -> "{}"))
                        .getMessage());
        assertEquals(0, flow.diagnostics().processedUnits());
        assertTrue(flow.diagnostics().calls() <= 7);
        assertEquals(2, flow.diagnostics().replans());
        var forged = workflow();
        assertEquals(
                "INVALID_CITATION",
                assertThrows(
                                IllegalStateException.class,
                                () ->
                                        run(
                                                forged,
                                                List.of(message("资料")),
                                                in -> {
                                                    try {
                                                        var node =
                                                                (ObjectNode)
                                                                        DailySummaryTestModel.JSON
                                                                                .readTree(
                                                                                        normal(in));
                                                        ((ObjectNode) node.path("facts").get(0))
                                                                .putArray("units")
                                                                .add(UUID.randomUUID().toString());
                                                        return node.toString();
                                                    } catch (Exception failure) {
                                                        throw new AssertionError(failure);
                                                    }
                                                }))
                        .getMessage());
        assertEquals(1, forged.diagnostics().calls());
        assertEquals(0, forged.diagnostics().processedUnits());
    }

    @Test
    void simpleAndAllChatDaysNeedNoOrganizationAndStaleDraftCannotComplete() {
        var flow = workflow();
        var simple = run(flow, List.of(message("资料")), null);
        assertEquals(3, simple.diagnostics().calls());
        assertFalse(
                simple.diagnostics().trace().stream()
                        .anyMatch(s -> s.targetAction().equals("ORGANIZE_TOPIC")));
        Object material = ReflectionTestUtils.getField(flow, "material");
        int version = (int) ReflectionTestUtils.getField(material, "factsVersion");
        ReflectionTestUtils.setField(material, "factsVersion", version + 1);
        assertEquals(
                "SUMMARY_WORKFLOW_INVARIANT",
                assertThrows(
                                IllegalStateException.class,
                                () -> ReflectionTestUtils.invokeMethod(flow, "complete"))
                        .getMessage());
        var chat = run(workflow(), List.of(message("闲聊"), message("闲聊 收到")), null);
        assertEquals(1, chat.diagnostics().calls());
        assertEquals(2, chat.diagnostics().processedUnits());
        assertTrue(chat.report().contains("暂无"));
    }

    @Test
    void backgroundCanBeOmittedWithAReasonWhileTheActionMustRemain() {
        var result =
                run(
                        workflow(),
                        List.of(message("普通背景"), message("待办：小王周四前补齐压测结果")),
                        in -> {
                            if (action(in, "EDIT_MATERIAL")) {
                                var out = Q01DailyEditorialTest.parse(normal(in));
                                for (var fact : out.path("facts")) {
                                    if (fact.path("kind").asText().equals("TOPIC")) {
                                        ((ObjectNode) fact)
                                                .put("value", "OMIT")
                                                .put("reason", "LOW_VALUE")
                                                .put("detail", "");
                                    }
                                }
                                return out.toString();
                            }
                            return normal(in);
                        });
        assertEquals(Map.of("LOW_VALUE", 1), result.diagnostics().omissionReasons());
        assertEquals(1, result.diagnostics().selectedCount());
        assertEquals(1, result.diagnostics().criticalCovered());
        assertTrue(result.report().contains("小王周四前补齐压测结果"));
        assertFalse(result.report().contains("已讨论发布安排"));
        evidence("selection-omissions", result);
    }

    private static void evidence(String name, DailySummaryWorkflow.Result result) {
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/daily-evidence"));
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("target/daily-evidence/business-" + name + ".json"),
                    DailySummaryTestModel.JSON
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(result.diagnostics()));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void criticalActionCannotBeOmittedToSatisfyAShortReport() {
        var flow = workflow();
        assertEquals(
                "SUMMARY_REQUIRED_FACT_MISSING",
                assertThrows(
                                IllegalStateException.class,
                                () ->
                                        run(
                                                flow,
                                                List.of(message("待办：小王周四前补齐压测结果")),
                                                in -> {
                                                    if (action(in, "EDIT_MATERIAL")) {
                                                        var out =
                                                                Q01DailyEditorialTest.parse(
                                                                        normal(in));
                                                        ((ObjectNode) out.path("facts").get(0))
                                                                .put("value", "OMIT")
                                                                .put("reason", "LOW_VALUE")
                                                                .put("detail", "");
                                                        return out.toString();
                                                    }
                                                    return normal(in);
                                                }))
                        .getMessage());
        assertEquals(1, flow.diagnostics().processedUnits());
        assertEquals(0, flow.diagnostics().criticalCovered());
        assertTrue(flow.diagnostics().calls() <= 8);
    }

    @Test
    void repeatedParagraphMappingsDoNotBypassTheReportBudget() {
        int[] draft = {0};
        var result =
                run(
                        workflow(),
                        List.of(message("待办：小王周四前补齐压测结果")),
                        in -> {
                            String raw = normal(in);
                            if (action(in, "WRITE_REPORT") && draft[0]++ == 0) {
                                try {
                                    var node =
                                            (ObjectNode) DailySummaryTestModel.JSON.readTree(raw);
                                    var list = (ArrayNode) node.path("paragraphs");
                                    list.add(list.get(0).deepCopy());
                                    return node.toString();
                                } catch (Exception failure) {
                                    throw new AssertionError(failure);
                                }
                            }
                            return raw;
                        });
        assertTrue(
                result.diagnostics().trace().stream()
                        .anyMatch(s -> s.feedbackCode().equals("DUPLICATE_FACTS")));
        assertEquals(1, result.report().split("小王周四前补齐压测结果", -1).length - 1);
    }

    @Test
    void wideTopicReconcilesDuplicatesAcrossOrganizationBatchBoundaries() {
        var messages = new ArrayList<SourceMessage>();
        for (int i = 0; i < 30; i++) {
            messages.add(message("相同结论"));
        }
        var flow = workflow();
        var result =
                run(
                        flow,
                        messages,
                        in -> {
                            if (action(in, "READ_BATCH")) {
                                var out = DailySummaryTestModel.JSON.createObjectNode();
                                var facts = out.putArray("facts");
                                var disposition = out.putArray("dispositions");
                                int index = 0;
                                for (var unit : in.path("units")) {
                                    facts.addObject()
                                            .put("kind", "CONCLUSION")
                                            .put("text", "采用方案甲")
                                            .put("topic", "发布")
                                            .put("owner", "")
                                            .put("due", "")
                                            .put("value", "REQUIRED")
                                            .put("reason", "DECISION")
                                            .putArray("units")
                                            .add(unit.path("id").asText());
                                    disposition
                                            .addObject()
                                            .put("unit", unit.path("id").asText())
                                            .putArray("facts")
                                            .add(index++);
                                }
                                return out.toString();
                            }
                            if (action(in, "EDIT_MATERIAL")) {
                                var out = DailySummaryTestModel.JSON.createObjectNode();
                                var fact =
                                        out.putArray("facts")
                                                .addObject()
                                                .put("kind", "CONCLUSION")
                                                .put("text", "采用方案甲")
                                                .put("topic", "发布")
                                                .put("owner", "")
                                                .put("due", "")
                                                .put("relation", "MERGE");
                                var inputs = fact.putArray("inputs");
                                in.path("facts").forEach(f -> inputs.add(f.path("id").asText()));
                                return out.toString();
                            }
                            return normal(in);
                        });
        assertEquals(1, result.diagnostics().factCount());
        assertEquals(1, result.diagnostics().criticalCovered());
        assertTrue(result.diagnostics().revisionCount() >= 3);
        var active = activeFacts(flow);
        assertEquals(
                30, ((DailySummaryFacts.Fact) active.values().iterator().next()).sources().size());
    }

    private static Map<?, ?> activeFacts(DailySummaryWorkflow flow) {
        Object material = ReflectionTestUtils.getField(flow, "material");
        return (Map<?, ?>) ReflectionTestUtils.getField(material, "facts");
    }
}
