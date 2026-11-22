/**
 * [INPUT]: 提供可控的作业状态和模型分发回执。
 * [OUTPUT]: 验证调用限额、超时和取消在下一次分发前生效。
 * [POS]: 日报模型边界测试，直接调用生成组件与预算。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.qqbot.MemoryProperties;
import dev.qqbot.model.GenerationObserver;
import dev.qqbot.model.TextGenerator;
import dev.qqbot.persistence.Db;
import dev.qqbot.schedule.DailySummaryGeneration.Budget;
import dev.qqbot.schedule.DailySummaryService.Snapshot.Header;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class DailySummaryGenerationTest {
    private static final int CALL_LIMIT = 40;
    private static final String PROMPT = "资料";
    private final Header header =
            new Header(UUID.randomUUID(), UUID.randomUUID(), 1, LocalDate.EPOCH);
    private final Db db = mock(Db.class);
    private final TextGenerator model = mock(TextGenerator.class);
    private final DailySummaryGeneration generation =
            new DailySummaryGeneration(db, model, MemoryProperties.defaults());

    @Test
    void generate_stopsAtCallLimitBeforeDispatch() {
        setActive(true);
        when(model.generate(any(), any())).thenAnswer(call -> dispatched(call.getArgument(1)));
        Budget budget = newBudget();
        for (int attempt = 0; attempt < CALL_LIMIT; attempt++) {
            generation.generate(header, PROMPT, budget);
        }

        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> generation.generate(header, PROMPT, budget));
        assertEquals("CALL_LIMIT", failure.getMessage());
        verify(model, times(CALL_LIMIT)).generate(any(), any());
    }

    @Test
    void generate_rejectsExpiredBudgetBeforeDispatch() {
        var expired = new Budget(System.nanoTime() - 1);
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> generation.generate(header, PROMPT, expired));
        assertEquals("SUMMARY_TIMEOUT", failure.getMessage());
        verifyNoInteractions(db, model);
    }

    @Test
    void generate_rejectsCancelledJobBeforeDispatch() {
        setActive(false);
        assertThrows(
                CancellationException.class,
                () -> generation.generate(header, PROMPT, newBudget()));
        verifyNoInteractions(model);
    }

    private void setActive(boolean isActive) {
        when(db.<Boolean>find(
                        anyString(),
                        any(),
                        eq(header.id()),
                        eq(header.group()),
                        eq(header.epoch())))
                .thenReturn(isActive ? Optional.of(true) : Optional.empty());
    }

    private static TextGenerator.Result dispatched(GenerationObserver observer) {
        observer.dispatched();
        return new TextGenerator.Result("结果", null);
    }

    private static Budget newBudget() {
        return new Budget(System.nanoTime() + Duration.ofMinutes(1).toNanos());
    }
}
