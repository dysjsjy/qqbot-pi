package dev.qqbot.pi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GenerationBudgetTest {
    long seconds(long value) {
        return TimeUnit.SECONDS.toNanos(value);
    }

    @Test
    void inputSizeSelectsThreeInitialTiers() {
        var basic = new GenerationBudget(0, 60, 300, 4000);
        var medium = new GenerationBudget(0, 60, 300, 4001);
        var large = new GenerationBudget(0, 60, 300, 12001);
        assertNull(basic.failure(seconds(59)));
        assertEquals("PI_FIRST_CONTENT_TIMEOUT", basic.failure(seconds(60)));
        assertNull(medium.failure(seconds(119)));
        assertEquals("PI_FIRST_CONTENT_TIMEOUT", medium.failure(seconds(120)));
        assertNull(large.failure(seconds(179)));
        assertEquals("PI_FIRST_CONTENT_TIMEOUT", large.failure(seconds(180)));
    }

    @Test
    void progressExtendsBeyondSixtyButNeverPastAbsoluteDeadline() {
        var budget = new GenerationBudget(0, 60, 300, 10);
        for (int elapsed = 30; elapsed < 300; elapsed += 30) {
            budget.progress(seconds(elapsed));
            assertNull(budget.failure(seconds(elapsed)));
        }
        budget.progress(seconds(300));
        assertEquals("PI_DEADLINE", budget.failure(seconds(300)));
    }

    @Test
    void inactivityAndLateProgressCannotReviveExpiredGeneration() {
        var budget = new GenerationBudget(0, 60, 300, 10);
        budget.progress(seconds(10));
        assertNull(budget.failure(seconds(69)));
        budget.progress(seconds(70));
        assertEquals("PI_IDLE_TIMEOUT", budget.failure(seconds(70)));
        budget.progress(seconds(71));
        assertEquals("PI_IDLE_TIMEOUT", budget.failure(seconds(71)));
        var first = new GenerationBudget(0, 60, 300, 10);
        first.progress(seconds(61));
        assertEquals("PI_FIRST_CONTENT_TIMEOUT", first.failure(seconds(61)));
    }

    @Test
    void callerDeadlineWinsOverInitialTierAndConfigurationRejectsNonPositiveLimits() {
        var budget = new GenerationBudget(0, 60, 20, 20000);
        budget.progress(seconds(19));
        assertEquals("PI_DEADLINE", budget.failure(seconds(20)));
        assertThrows(IllegalArgumentException.class, () -> new GenerationBudget(0, 0, 300, 1));
        assertThrows(IllegalArgumentException.class, () -> new GenerationBudget(0, 60, 0, 1));
    }
}
