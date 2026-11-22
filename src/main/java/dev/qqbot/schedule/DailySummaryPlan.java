/**
 * [INPUT]: 依赖当前尝试的可信来源、已接受内容和步骤预算。
 * [OUTPUT]: 维护阶段、步骤队列及失败重排后的版本。
 * [POS]: 日报工作流的队列状态。模型请求和校验由工作流调用。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static dev.qqbot.schedule.DailySummaryWorkflow.invariant;

import dev.qqbot.MemoryProperties;
import dev.qqbot.schedule.DailySummaryFacts.Feedback;
import dev.qqbot.schedule.DailySummaryFacts.SourceUnit;
import dev.qqbot.schedule.DailySummaryPlan.PlannedStep;
import dev.qqbot.schedule.DailySummaryPlan.Recovery.Decision;
import dev.qqbot.schedule.DailySummaryPlan.Recovery.Failure;
import dev.qqbot.schedule.DailySummaryPlanner.Action;
import dev.qqbot.schedule.DailySummaryPlanner.Strategy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

final class DailySummaryPlan {
    private static final int EDIT_TOKENS_PER_FACT = 100;
    private static final int MAX_IMMEDIATE_REPAIRS = 2;
    private final MemoryProperties budgets;
    private final DailySummarySources sources;
    private final DailySummaryMaterial material;
    private final Recovery recovery = new Recovery();
    private final Deque<PlannedStep> pending = new ArrayDeque<>();
    private Action phase = Action.READ_BATCH;
    private int editGroups;
    private int writingGroups;
    private boolean isFinalEdit;
    private boolean hasGlobalReview;
    private boolean hasRefinedSelection;
    private int noProgress;
    private int switches;
    private int replans;
    private String lastFeedback = "";

    DailySummaryPlan(
            MemoryProperties budgets, DailySummarySources sources, DailySummaryMaterial material) {
        this.budgets = budgets;
        this.sources = sources;
        this.material = material;
    }

    void planReads(ToIntFunction<List<SourceUnit>> estimate) {
        var budget = new Budget(budgets.summaryOutputTokens(), budgets.inputTokens(), estimate);
        List<List<String>> batches = planReadBatches(sources.ordered(), budget);
        // SIDE EFFECT: 完整批次计划可用后才加入步骤队列。
        for (List<String> batch : batches) {
            pending.addLast(PlannedStep.plan(Action.READ_BATCH, batch, Strategy.NORMAL));
        }
    }

    boolean hasPending() {
        return !pending.isEmpty();
    }

    PlannedStep next() {
        PlannedStep step = pending.getFirst();
        if (step.action() != phase) {
            throw invariant();
        }
        return step;
    }

    boolean advance(ToIntFunction<PlannedStep> estimate) {
        switch (phase) {
            case READ_BATCH -> enqueueEditorialBatches();
            case EDIT_MATERIAL -> advanceEditorialPhase(estimate);
            case SELECT_FACTS -> enqueueReportDraft();
            case WRITE_REPORT -> {
                return enqueueFinalEdit();
            }
            default -> throw invariant();
        }
        return true;
    }

    private void enqueueEditorialBatches() {
        material.requireReadCoverage(sources.size());
        int maximumFacts = Math.max(1, budgets.summaryOutputTokens() / EDIT_TOKENS_PER_FACT);
        enqueue(Action.EDIT_MATERIAL, material.factIds(), maximumFacts, Strategy.NORMAL);
        phase = Action.EDIT_MATERIAL;
    }

    private void advanceEditorialPhase(ToIntFunction<PlannedStep> estimate) {
        if (editGroups <= 1 || hasGlobalReview) {
            material.useEditorialChoices();
            phase = Action.SELECT_FACTS;
            return;
        }
        List<String> candidates = material.editorialCandidates();
        var review = PlannedStep.plan(Action.EDIT_MATERIAL, candidates, Strategy.GLOBAL_EDIT);
        if (!candidates.isEmpty() && estimate.applyAsInt(review) <= budgets.inputTokens()) {
            hasGlobalReview = true;
            pending.add(review);
            return;
        }
        enqueue(
                Action.SELECT_FACTS,
                material.factIds(),
                Math.max(1, material.factCount()),
                Strategy.NORMAL);
        phase = Action.SELECT_FACTS;
    }

    private void enqueueReportDraft() {
        material.confirmSelection(sources.size());
        List<String> selected = material.selectedFactIds();
        Strategy strategy = hasRefinedSelection ? Strategy.BRIEF : Strategy.NORMAL;
        enqueue(Action.WRITE_REPORT, selected, Math.max(1, selected.size()), strategy);
        phase = Action.WRITE_REPORT;
    }

    private void enqueue(Action action, List<String> ids, int size, Strategy strategy) {
        for (int start = 0; start < ids.size(); start += size) {
            var batch = ids.subList(start, Math.min(ids.size(), start + size));
            pending.addLast(PlannedStep.plan(action, batch, strategy));
        }
    }

    private boolean enqueueFinalEdit() {
        if (isFinalEdit || writingGroups <= 1 || material.paragraphs().isEmpty()) {
            return false;
        }
        isFinalEdit = true;
        pending.add(
                PlannedStep.plan(
                        Action.WRITE_REPORT, material.selectedFactIds(), Strategy.GLOBAL_EDIT));
        return true;
    }

    void acceptPlaceholder() {
        pending.removeFirst();
        lastFeedback = "";
    }

    void accept(PlannedStep step) {
        acceptPlaceholder();
        if (step.action() == Action.EDIT_MATERIAL) {
            editGroups++;
        }
        if (step.action() == Action.WRITE_REPORT && !isFinalEdit) {
            writingGroups++;
        }
        noProgress = 0;
        switches = 0;
    }

    String repair(PlannedStep step, Feedback feedback) {
        noProgress++;
        lastFeedback = feedback.code();
        if (noProgress >= MAX_IMMEDIATE_REPAIRS || step.failures() >= MAX_IMMEDIATE_REPAIRS) {
            String reason = replan(step, feedback, false);
            noProgress = 0;
            return reason;
        }
        pending.removeFirst();
        pending.addFirst(step.repair(feedback));
        return "REPAIR_CURRENT";
    }

    String replan(PlannedStep step, Feedback feedback, boolean isOversized) {
        Failure failure =
                Recovery.nextFailure(
                        step, feedback, new Failure(isOversized, isFinalEdit, switches));
        if (Recovery.shouldRefineSelection(step, feedback, hasRefinedSelection)) {
            restartSelection(feedback);
            recordReplan(failure);
            return "RESELECT_FACTS";
        }
        SourceUnit first =
                step.action() == Action.READ_BATCH ? sources.requireUnit(step.ids().get(0)) : null;
        Decision decision = recovery.planRecovery(step, feedback, failure, first);
        applyRecovery(decision);
        recordReplan(failure);
        return "INPUT_GROUP_CHANGED";
    }

    private void restartSelection(Feedback feedback) {
        var next =
                PlannedStep.recover(
                        Action.SELECT_FACTS, material.factIds(), Strategy.BRIEF, feedback);
        // SIDE EFFECT: 放弃旧稿和待写步骤，开始新一轮选材。
        material.refineSelection();
        pending.clear();
        pending.addLast(next);
        hasRefinedSelection = true;
        phase = Action.SELECT_FACTS;
        writingGroups = 0;
        isFinalEdit = false;
    }

    private void applyRecovery(Decision decision) {
        // SIDE EFFECT: 来源和队首一起替换，避免重读已完成批次。
        var replacement = decision.replacement();
        if (replacement != null) {
            sources.replace(replacement.original(), replacement.split());
        }
        pending.removeFirst();
        List<PlannedStep> steps = decision.steps();
        for (int index = steps.size() - 1; index >= 0; index--) {
            pending.addFirst(steps.get(index));
        }
    }

    private void recordReplan(Failure failure) {
        switches = failure.switches();
        replans++;
    }

    boolean isFinalEdit() {
        return isFinalEdit;
    }

    boolean isComplete() {
        return pending.isEmpty() && lastFeedback.isEmpty();
    }

    int replans() {
        return replans;
    }

    record PlannedStep(Action action, List<String> ids, Strategy strategy, Retry retry) {
        PlannedStep {
            ids = List.copyOf(ids);
        }

        static PlannedStep plan(Action action, List<String> ids, Strategy strategy) {
            return recover(action, ids, strategy, null);
        }

        static PlannedStep recover(
                Action action, List<String> ids, Strategy strategy, Feedback feedback) {
            return new PlannedStep(action, ids, strategy, new Retry(0, feedback));
        }

        PlannedStep repair(Feedback feedback) {
            return new PlannedStep(
                    action, ids, Strategy.FIX_RESULT, new Retry(retry.failures() + 1, feedback));
        }

        int failures() {
            return retry.failures();
        }

        Feedback feedback() {
            return retry.feedback();
        }

        record Retry(int failures, Feedback feedback) {}
    }

    private static final int MAX_BATCH_UNITS = 64;
    private static final int OUTPUT_TOKENS_PER_UNIT = 14;
    private static final int MIN_UNITS_BEFORE_SILENCE_BREAK = 36;
    private static final int SILENCE_BREAK_SECONDS = 1800;

    static List<List<String>> planReadBatches(Collection<SourceUnit> units, Budget budget) {
        var batches = new ArrayList<List<String>>();
        Batch current = new Batch();
        for (SourceUnit unit : units) {
            if (current.startsNewBatch(unit, budget)) {
                batches.add(List.copyOf(current.ids));
                current = new Batch();
            }
            current.append(unit);
        }
        if (!current.ids.isEmpty()) {
            batches.add(List.copyOf(current.ids));
        }
        return List.copyOf(batches);
    }

    record Budget(int outputTokens, int inputTokens, ToIntFunction<List<SourceUnit>> estimate) {
        int maxUnits() {
            return Math.max(1, Math.min(MAX_BATCH_UNITS, outputTokens / OUTPUT_TOKENS_PER_UNIT));
        }
    }

    private static final class Batch {
        private final List<String> ids = new ArrayList<>();
        private final List<SourceUnit> textUnits = new ArrayList<>();

        void append(SourceUnit unit) {
            ids.add(unit.id());
            if (!unit.text().isEmpty()) {
                textUnits.add(unit);
            }
        }

        boolean startsNewBatch(SourceUnit unit, Budget budget) {
            if (ids.isEmpty() || unit.text().isEmpty()) {
                return false;
            }
            return followsSilence(unit)
                    || textUnits.size() >= budget.maxUnits()
                    || estimateWith(unit, budget) > budget.inputTokens();
        }

        private boolean followsSilence(SourceUnit next) {
            if (textUnits.size() < MIN_UNITS_BEFORE_SILENCE_BREAK) {
                return false;
            }
            SourceUnit previous = textUnits.get(textUnits.size() - 1);
            return DailySummaryPreprocessor.gapSeconds(previous.time(), next.time())
                    >= SILENCE_BREAK_SECONDS;
        }

        private int estimateWith(SourceUnit unit, Budget budget) {
            var candidate = new ArrayList<>(textUnits);
            candidate.add(unit);
            return budget.estimate().applyAsInt(List.copyOf(candidate));
        }
    }

    static final class Recovery {
        private static final int MAX_SWITCHES = 2;
        private static final Set<String> RESELECT_FEEDBACK =
                Set.of("REPORT_TOO_LONG", "REQUEST_TOO_LARGE");
        private final DailySummaryPlanner planner = new DailySummaryPlanner();

        Decision planRecovery(
                PlannedStep step, Feedback feedback, Failure failure, SourceUnit source) {
            var recovery =
                    planner.recover(step.action(), feedback, step.ids().size(), failure.switches());
            if (recovery.split() && !failure.isFinalEdit()) {
                return splitBatch(step, recovery.strategy(), feedback);
            }
            if (!failure.isOversized()) {
                var retry =
                        PlannedStep.recover(
                                step.action(), step.ids(), recovery.strategy(), feedback);
                return new Decision(List.of(retry), null);
            }
            if (step.action() != Action.READ_BATCH) {
                throw new IllegalStateException("CONTEXT_TOO_LARGE");
            }
            return splitSource(source, recovery.strategy(), feedback);
        }

        private static Decision splitBatch(PlannedStep step, Strategy strategy, Feedback feedback) {
            int midpoint = step.ids().size() / 2;
            var left =
                    PlannedStep.recover(
                            step.action(), step.ids().subList(0, midpoint), strategy, feedback);
            var right =
                    PlannedStep.recover(
                            step.action(),
                            step.ids().subList(midpoint, step.ids().size()),
                            strategy,
                            feedback);
            return new Decision(List.of(left, right), null);
        }

        private static Decision splitSource(
                SourceUnit source, Strategy strategy, Feedback feedback) {
            DailySummarySources.Split split = DailySummarySources.bisectSource(source);
            var left =
                    PlannedStep.recover(
                            Action.READ_BATCH, List.of(split.left().id()), strategy, feedback);
            var right =
                    PlannedStep.recover(
                            Action.READ_BATCH, List.of(split.right().id()), strategy, feedback);
            return new Decision(List.of(left, right), new Replacement(source.id(), split));
        }

        static Failure nextFailure(PlannedStep step, Feedback feedback, Failure previous) {
            int switches = previous.switches() + (previous.isOversized() ? 0 : 1);
            if (!previous.isOversized() && switches > MAX_SWITCHES) {
                throw recoveryExhausted(step, feedback.code());
            }
            return new Failure(previous.isOversized(), previous.isFinalEdit(), switches);
        }

        static boolean shouldRefineSelection(
                PlannedStep step, Feedback feedback, boolean hasRefinedSelection) {
            return step.action() == Action.WRITE_REPORT
                    && !hasRefinedSelection
                    && RESELECT_FEEDBACK.contains(feedback.code());
        }

        private static IllegalStateException recoveryExhausted(
                PlannedStep step, String feedbackCode) {
            String code =
                    switch (feedbackCode) {
                        case "REQUIRED_FACT_MISSING" -> "SUMMARY_REQUIRED_FACT_MISSING";
                        case "MISSING_CITATION", "EMPTY_REPORT" -> feedbackCode;
                        case "RESPONSE_FORMAT" ->
                                step.ids().size() == 1
                                        ? "SUMMARY_RESULT_INVALID"
                                        : "SUMMARY_NO_PROGRESS";
                        default -> "SUMMARY_NO_PROGRESS";
                    };
            return new IllegalStateException(code);
        }

        record Failure(boolean isOversized, boolean isFinalEdit, int switches) {}

        record Decision(List<PlannedStep> steps, Replacement replacement) {}

        record Replacement(String original, DailySummarySources.Split split) {}
    }
}
