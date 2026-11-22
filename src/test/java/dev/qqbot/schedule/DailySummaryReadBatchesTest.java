/**
 * [INPUT]: 使用有序来源和确定性的请求估算函数。
 * [OUTPUT]: 验证静默、预算与空文本占位的分批边界。
 * [POS]: 日报批次规划纯函数测试，不写入队列。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.qqbot.schedule.DailySummaryFacts.SourceUnit;
import dev.qqbot.schedule.DailySummaryPlan.Budget;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DailySummaryReadBatchesTest {
    private static final String EARLY = "2026-11-22T08:00:00Z";
    private static final String LATE = "2026-11-22T08:30:00Z";
    private static final String TEXT = "议题";

    @Test
    void planReadBatches_countsOnlyTextUnitsBeforeSilenceBreak() {
        var units = new ArrayList<SourceUnit>();
        for (int index = 0; index < 36; index++) {
            units.add(unit(index, EARLY, TEXT));
        }
        units.add(unit(36, LATE, ""));
        units.add(unit(37, LATE, TEXT));

        List<List<String>> batches =
                DailySummaryPlan.planReadBatches(units, new Budget(900, 100, List::size));

        assertEquals(List.of(37, 1), batches.stream().map(List::size).toList());
        assertEquals("u36", batches.get(0).get(36));
        assertEquals(List.of("u37"), batches.get(1));
    }

    @Test
    void planReadBatches_preservesOversizedUnitForRecoveryAndKeepsPlaceholderBeforeIt() {
        List<SourceUnit> units = List.of(unit(0, EARLY, ""), unit(1, EARLY, TEXT));

        List<List<String>> batches =
                DailySummaryPlan.planReadBatches(units, new Budget(100, 0, List::size));

        assertEquals(List.of(List.of("u0"), List.of("u1")), batches);
    }

    @Test
    void planReadBatches_respectsTheSubstantiveUnitLimit() {
        List<SourceUnit> units =
                List.of(unit(0, EARLY, TEXT), unit(1, EARLY, ""), unit(2, EARLY, TEXT));

        List<List<String>> batches =
                DailySummaryPlan.planReadBatches(units, new Budget(14, 100, List::size));

        assertEquals(List.of(List.of("u0", "u1"), List.of("u2")), batches);
    }

    private static SourceUnit unit(int index, String time, String text) {
        return new SourceUnit("u" + index, "", index, 0, text.length(), time, "", text);
    }
}
