/**
 * [INPUT]: 提供部分及完整的模型用量报告。
 * [OUTPUT]: 验证用量字段合并和完整报告不会被未知报告覆盖。
 * [POS]: 计量观察的纯规则测试。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.usage;

import static org.junit.jupiter.api.Assertions.*;

import dev.qqbot.model.ModelUsage;
import dev.qqbot.model.UsageStatus;
import dev.qqbot.usage.MeteredTextGenerator.CallObservation;
import org.junit.jupiter.api.Test;

class ModelCallObservationTest {
    @Test
    void reconcileUsage_preservesPreviousCountsAndAcceptsReportedZero() {
        var previous = new ModelUsage(100L, 12L, null, null, 8L, UsageStatus.UNKNOWN);
        var incomplete = new ModelUsage(null, 24L, 0L, null, null, UsageStatus.REPORTED);

        var merged = CallObservation.reconcileUsage(previous, incomplete).orElseThrow();

        assertEquals(new ModelUsage(100L, 24L, 0L, null, 8L, UsageStatus.UNKNOWN), merged);
    }

    @Test
    void reconcileUsage_acceptsCompleteReportAndRejectsRegression() {
        var complete = new ModelUsage(100L, 24L, 0L, 0L, null, UsageStatus.REPORTED);
        assertEquals(
                complete,
                CallObservation.reconcileUsage(ModelUsage.unknown(), complete).orElseThrow());
        assertTrue(CallObservation.reconcileUsage(complete, ModelUsage.unknown()).isEmpty());
    }
}
