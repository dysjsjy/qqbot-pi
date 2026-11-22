/**
 * [INPUT]: 使用合成来源单元和读批、选材响应。
 * [OUTPUT]: 验证被拒绝的整批结果不会留下部分事实或选材。
 * [POS]: 日报内容状态测试，不调用模型或数据库。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.qqbot.schedule.DailySummaryFacts.SourceUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class DailySummaryMaterialTest {
    private static final String FIRST_UNIT = "u0";
    private static final String SECOND_UNIT = "u1";
    private static final String FIRST_FACT = "f1";
    private static final String SECOND_FACT = "f2";
    private static final String INVARIANT = "SUMMARY_WORKFLOW_INVARIANT";

    @Test
    void acceptRead_rejectsAlreadyProcessedUnitWithoutAddingFacts() {
        DailySummaryMaterial material = readMaterial();

        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                material.acceptRead(
                                        readResponse(FIRST_UNIT), List.of(source(FIRST_UNIT, 0))));

        assertEquals(INVARIANT, failure.getMessage());
        assertEquals(List.of(FIRST_FACT, SECOND_FACT), material.factIds());
        assertEquals(2, material.processedCount());
    }

    @Test
    void acceptSelection_rejectsOverlappingBatchBeforeAddingAnyChoices() {
        DailySummaryMaterial material = readMaterial();
        material.acceptSelection(
                """
                {"selected":[{"id":"f1","detail":"FULL"}],"omitted":[]}
                """,
                List.of(FIRST_FACT),
                2);

        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                material.acceptSelection(
                                        """
                {"selected":[{"id":"f2","detail":"FULL"},{"id":"f1","detail":"BRIEF"}],"omitted":[]}
                """,
                                        List.of(SECOND_FACT, FIRST_FACT),
                                        2));

        assertEquals(INVARIANT, failure.getMessage());
        assertEquals(List.of(FIRST_FACT), material.selectedFactIds());
    }

    private static DailySummaryMaterial readMaterial() {
        var material = new DailySummaryMaterial(SummaryProfile.defaults());
        material.acceptRead(readResponse(FIRST_UNIT), List.of(source(FIRST_UNIT, 0)));
        material.acceptRead(readResponse(SECOND_UNIT), List.of(source(SECOND_UNIT, 1)));
        return material;
    }

    private static SourceUnit source(String unitId, int order) {
        return new SourceUnit(
                unitId,
                "00000000-0000-0000-0000-00000000000" + order,
                order,
                0,
                6,
                "",
                "讨论发布安排",
                "讨论发布安排");
    }

    private static String readResponse(String unitId) {
        return """
                {"facts":[{"kind":"TOPIC","text":"讨论发布安排","topic":"发布","owner":"","due":"","units":["%s"]}],
                "ignored":{}}
                """
                .formatted(unitId);
    }
}
