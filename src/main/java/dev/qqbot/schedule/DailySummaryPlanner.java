/**
 * [INPUT]: 接收当前步骤动作、校验反馈码、当前批次宽度与历史切换计数。
 * [OUTPUT]: 提供下一步规划动作（Action）与失败恢复策略（Recovery）。
 * [POS]: schedule 模块的轻量状态机控制器。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import dev.qqbot.schedule.DailySummaryFacts.Feedback;
import java.util.Set;

/** A small business controller, not a model with arbitrary tools. */
public final class DailySummaryPlanner {
    public enum Action {
        READ_BATCH,
        EDIT_MATERIAL,
        ORGANIZE_TOPIC,
        SELECT_FACTS,
        WRITE_REPORT,
        REPAIR_RESULT,
        COMPLETE
    }

    public enum Strategy {
        NORMAL,
        FIX_RESULT,
        SMALLER_BATCH,
        COMPACT_INDEX,
        COMPACT_FACTS,
        BRIEF,
        TARGETED_REWRITE,
        GLOBAL_EDIT
    }

    public record Recovery(Strategy strategy, boolean split) {}

    public Action next(
            boolean unread, boolean unorganized, boolean selectionPending, boolean draftPending) {
        if (unread) {
            return Action.READ_BATCH;
        }
        if (unorganized) {
            return Action.ORGANIZE_TOPIC;
        }
        if (selectionPending) {
            return Action.SELECT_FACTS;
        }
        if (draftPending) {
            return Action.WRITE_REPORT;
        }
        return Action.COMPLETE;
    }

    public Recovery recover(Action action, Feedback feedback, int width, int previousSwitches) {
        return switch (action) {
            case READ_BATCH ->
                    new Recovery(
                            width > 1
                                    ? Strategy.SMALLER_BATCH
                                    : previousSwitches < 2
                                            ? Strategy.COMPACT_FACTS
                                            : Strategy.BRIEF,
                            width > 1);
            case EDIT_MATERIAL, ORGANIZE_TOPIC ->
                    new Recovery(
                            previousSwitches < 2 ? Strategy.COMPACT_FACTS : Strategy.BRIEF,
                            width > 1);
            case SELECT_FACTS ->
                    new Recovery(
                            previousSwitches == 0 ? Strategy.COMPACT_INDEX : Strategy.BRIEF,
                            width > 1);
            case WRITE_REPORT ->
                    new Recovery(
                            Set.of("REQUIRED_FACT_MISSING", "SPEAKER_LEAKED")
                                            .contains(feedback.code())
                                    ? Strategy.TARGETED_REWRITE
                                    : Strategy.BRIEF,
                            width > 1);
            default -> throw new IllegalStateException("SUMMARY_WORKFLOW_INVARIANT");
        };
    }
}
