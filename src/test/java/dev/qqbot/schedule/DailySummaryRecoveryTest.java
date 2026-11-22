/**
 * [INPUT]: 提供日报读取步骤、可信来源和超预算反馈。
 * [OUTPUT]: 验证拆批顺序、单条来源二分和恢复上限。
 * [POS]: 重排计划的纯规则测试。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.qqbot.schedule.DailySummaryFacts.Feedback;
import dev.qqbot.schedule.DailySummaryFacts.SourceUnit;
import dev.qqbot.schedule.DailySummaryPlan.PlannedStep;
import dev.qqbot.schedule.DailySummaryPlan.Recovery;
import dev.qqbot.schedule.DailySummaryPlan.Recovery.Failure;
import dev.qqbot.schedule.DailySummaryPlanner.Action;
import dev.qqbot.schedule.DailySummaryPlanner.Strategy;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DailySummaryRecoveryTest {
    private static final String SOURCE_ID = "u1";
    private static final Feedback OVERSIZED = new Feedback("REQUEST_TOO_LARGE", List.of(), 100, 50);
    private static final Failure SPLIT = new Failure(true, false, 0);
    private final Recovery recovery = new Recovery();

    @Test
    void planRecovery_splitsBatchInOriginalOrder() {
        var first = source();
        var step =
                PlannedStep.plan(
                        Action.READ_BATCH, List.of(first.id(), "u2", "u3"), Strategy.NORMAL);

        var decision = recovery.planRecovery(step, OVERSIZED, SPLIT, first);

        assertThat(decision.steps())
                .extracting(PlannedStep::ids)
                .containsExactly(List.of(first.id()), List.of("u2", "u3"));
        assertThat(decision.replacement()).isNull();
    }

    @Test
    void planRecovery_splitsSingleSourceIntoTwoOrderedSteps() {
        var first = source();
        var step = PlannedStep.plan(Action.READ_BATCH, List.of(first.id()), Strategy.NORMAL);

        var decision = recovery.planRecovery(step, OVERSIZED, SPLIT, first);
        var replacement = decision.replacement();

        assertThat(replacement.original()).isEqualTo(first.id());
        assertThat(decision.steps())
                .extracting(PlannedStep::ids)
                .containsExactly(
                        List.of(replacement.split().left().id()),
                        List.of(replacement.split().right().id()));
        assertThat(replacement.split().left().text() + replacement.split().right().text())
                .isEqualTo(first.text());
    }

    @Test
    void nextFailure_reportsRequiredFactWhenRecoveryRunsOut() {
        var step = PlannedStep.plan(Action.WRITE_REPORT, List.of("f1"), Strategy.NORMAL);
        var missingFact = new Feedback("REQUIRED_FACT_MISSING", step.ids(), 0, 1);

        assertThatThrownBy(
                        () -> Recovery.nextFailure(step, missingFact, new Failure(false, true, 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SUMMARY_REQUIRED_FACT_MISSING");
    }

    private static SourceUnit source() {
        String text = "合成来源供读批纯测试";
        return new SourceUnit(
                SOURCE_ID, UUID.randomUUID().toString(), 0, 0, text.length(), "", "成员", text);
    }
}
