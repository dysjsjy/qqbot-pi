/**
 * [INPUT]: 使用合成日报来源、候选事实与已核验段落。
 * [OUTPUT]: 验证模型输入的结构、终稿改写与证据约束。
 * [POS]: 纯序列化测试，不调用模型。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.qqbot.schedule.DailySummaryFacts.Choice;
import dev.qqbot.schedule.DailySummaryFacts.Fact;
import dev.qqbot.schedule.DailySummaryFacts.Kind;
import dev.qqbot.schedule.DailySummaryFacts.Paragraph;
import dev.qqbot.schedule.DailySummaryFacts.SourceUnit;
import dev.qqbot.schedule.DailySummaryPlanner.Action;
import dev.qqbot.schedule.DailySummaryPlanner.Strategy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DailySummaryPromptTest {
    private static final String FACT_ID = "fact1";
    private static final String FIRST = "00000000-0000-0000-0000-000000000001";
    private static final String MIDDLE = "00000000-0000-0000-0000-000000000002";
    private static final String LAST = "00000000-0000-0000-0000-000000000003";
    private final DailySummaryPrompt prompt = new DailySummaryPrompt(SummaryProfile.defaults());

    @Test
    void renderRead_exposesOnlyModelSourceFields() throws Exception {
        String request =
                prompt.renderRead(
                        stage(Action.READ_BATCH, Strategy.NORMAL), List.of(unit(FIRST, 0)));

        JsonNode input = input(request);
        JsonNode source = input.required("units").required(0);
        assertEquals("READ_BATCH", input.required("action").asText());
        assertEquals(160, input.required("maxFactCharacters").asInt());
        assertEquals(5, source.size());
        assertEquals("u0", source.required("id").asText());
        assertFalse(source.has("messageId"));
        assertFalse(input.has("facts"));
    }

    @Test
    void renderFacts_rejectsMissingEvidenceBeforeReturningJson() {
        var material = new DailySummaryPrompt.Material(List.of(fact()), List.of(), Map.of());
        var stage = stage(Action.EDIT_MATERIAL, Strategy.NORMAL);

        var failure =
                assertThrows(
                        IllegalStateException.class, () -> prompt.renderFacts(stage, material));

        assertEquals("SUMMARY_WORKFLOW_INVARIANT", failure.getMessage());
        assertTrue(failure.getCause().getMessage().contains(FACT_ID));
    }

    @Test
    void renderDraft_reusesDraftTextAndKeepsOnlyEvidenceEndpointsForFinalEdit() throws Exception {
        var paragraph = new Paragraph("已核验的草稿", List.of(FACT_ID), Set.of(MIDDLE));
        var draft = new DailySummaryPrompt.Draft(350, true, List.of(paragraph));
        var stage = stage(Action.WRITE_REPORT, Strategy.GLOBAL_EDIT);

        JsonNode input = input(prompt.renderDraft(stage, material(), draft));
        JsonNode fact = input.required("facts").required(0);
        assertEquals(paragraph.text(), fact.required("text").asText());
        assertEquals(350, input.required("targetCharacters").asInt());
        assertEquals(1, input.required("drafts").size());
        assertEquals(3, fact.required("sourceCount").asInt());
        assertEquals(
                List.of(FIRST, LAST),
                DailySummaryJson.MAPPER.convertValue(fact.required("sources"), List.class));
    }

    private static DailySummaryPrompt.Material material() {
        var units = List.of(unit(FIRST, 0), unit(MIDDLE, 1), unit(LAST, 2));
        return new DailySummaryPrompt.Material(
                List.of(fact()), units, Map.of(FACT_ID, new Choice(FACT_ID, "FULL", "")));
    }

    private static Fact fact() {
        var refs = new LinkedHashSet<>(List.of(FIRST, MIDDLE, LAST));
        return new Fact(FACT_ID, Kind.CONCLUSION, "决定采用新方案", "方案", "", "", refs);
    }

    private static SourceUnit unit(String id, int order) {
        return new SourceUnit("u" + order, id, order, 0, 1, "2026-11-21T08:00:00Z", "成员", "方案");
    }

    private static DailySummaryPrompt.Stage stage(Action action, Strategy strategy) {
        return new DailySummaryPrompt.Stage(action, strategy, false, null);
    }

    private static JsonNode input(String request) throws Exception {
        String marker = "\nINPUT_JSON\n";
        int index = request.indexOf(marker);
        assertTrue(index >= 0);
        return DailySummaryJson.MAPPER.readTree(request.substring(index + marker.length()));
    }
}
