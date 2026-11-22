/**
 * [INPUT]: 依赖冻结消息、模型接口、步骤计划和尝试时钟。
 * [OUTPUT]: 依序生成并校验各阶段结果，返回带引用日报和诊断。
 * [POS]: 单次业务流程编排。阶段、内容和观测由各自的尝试状态负责。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import dev.qqbot.MemoryProperties;
import dev.qqbot.model.PromptPolicy;
import dev.qqbot.model.Purpose;
import dev.qqbot.model.TokenEstimator;
import dev.qqbot.schedule.DailySummaryFacts.Feedback;
import dev.qqbot.schedule.DailySummaryFacts.InvalidResult;
import dev.qqbot.schedule.DailySummaryFacts.SourceMessage;
import dev.qqbot.schedule.DailySummaryFacts.SourceUnit;
import dev.qqbot.schedule.DailySummaryPlan.PlannedStep;
import dev.qqbot.schedule.DailySummaryPlanner.Action;
import dev.qqbot.schedule.DailySummaryPlanner.Strategy;
import dev.qqbot.schedule.DailySummaryWorkflow.Diagnostics;
import dev.qqbot.schedule.DailySummaryWorkflow.Step;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class DailySummaryWorkflow {
    private static final int MAX_MODEL_CALLS = 40;
    private static final int MAX_STEPS = 120;
    private static final String ACCEPTED = "ACCEPTED";
    private static final String SYSTEM = PromptPolicy.systemPrompt(Purpose.DAILY_SUMMARY);
    private final MemoryProperties budgets;
    private final Lifecycle lifecycle;
    private final DailySummaryPrompt prompts;
    private final DailySummaryMaterial material;
    private final DailySummaryPlan plan;
    private final Progress progress;
    private final Map<String, String> memberNames;
    private final DailySummarySources sources = new DailySummarySources();
    private boolean hasStarted;
    private int calls;
    private int steps;

    /** 配置一次默认场景的日报生成。截止时间使用单调时钟纳秒。 */
    public DailySummaryWorkflow(
            MemoryProperties budgets, long deadline, Runnable guard, Consumer<Step> listener) {
        this(
                budgets,
                new Lifecycle(deadline, guard, System::nanoTime),
                listener,
                Audience.defaults());
    }

    /** 配置一次日报生成，显式提供期限、状态检查和群昵称。 */
    public DailySummaryWorkflow(
            MemoryProperties budgets,
            Lifecycle lifecycle,
            Consumer<Step> listener,
            Audience audience) {
        this.budgets = Objects.requireNonNull(budgets, "budgets must not be null");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.prompts = new DailySummaryPrompt(audience.profile());
        this.material = new DailySummaryMaterial(audience.profile());
        this.memberNames = audience.memberNames();
        this.plan = new DailySummaryPlan(budgets, sources, material);
        this.progress = new Progress(sources, material, listener);
    }

    /** 从非空的冻结消息生成日报。同一实例只能执行一次。 */
    public Result execute(List<SourceMessage> messages, Model model) {
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(model, "model must not be null");
        initializeAttempt(messages);
        return runSteps(model);
    }

    private void initializeAttempt(List<SourceMessage> messages) {
        if (hasStarted || messages.isEmpty()) {
            throw invariant();
        }
        sources.freeze(messages, memberNames);
        // SIDE EFFECT: 冻结来源后开始唯一一次尝试。
        hasStarted = true;
        plan.planReads(this::estimateRead);
    }

    private Result runSteps(Model model) {
        while (true) {
            checkStepLimit();
            if (plan.hasPending()) {
                runNextStep(model);
            } else if (!plan.advance(this::estimateStep)) {
                return finishAttempt();
            }
        }
    }

    private Result finishAttempt() {
        String report = complete();
        var step = PlannedStep.plan(Action.COMPLETE, List.of(), Strategy.NORMAL);
        record(step, new Outcome("", "COMPLETE", true, null));
        return new Result(report, diagnostics());
    }

    private String complete() {
        lifecycle.check();
        if (!plan.isComplete()) {
            throw invariant();
        }
        return material.renderReport(sources.byId());
    }

    private void runNextStep(Model model) {
        PlannedStep step = plan.next();
        if (step.action() == Action.READ_BATCH && sources.isMarkupOnly(step)) {
            acknowledgeMarkup(step);
            return;
        }
        String request = prompt(step);
        int estimate = estimateInput(request);
        if (estimate > budgets.inputTokens()) {
            replanOversized(step, request, estimate);
            return;
        }
        progress.recordEstimate(estimate);
        generate(step, request, model);
    }

    private void acknowledgeMarkup(PlannedStep step) {
        material.acceptPlaceholders(step.ids());
        plan.acceptPlaceholder();
        record(step, new Outcome("", ACCEPTED, true, null));
    }

    private void replanOversized(PlannedStep step, String request, int estimate) {
        var feedback =
                new Feedback("REQUEST_TOO_LARGE", List.of(), estimate, budgets.inputTokens());
        String reason = plan.replan(step, feedback, true);
        record(step, new Outcome(feedback.code(), reason, false, request));
    }

    private void generate(PlannedStep step, String request, Model model) {
        if (calls >= MAX_MODEL_CALLS) {
            throw new IllegalStateException("CALL_LIMIT");
        }
        // SIDE EFFECT: 单步模型调用计入本轮尝试限额。
        calls++;
        long started = lifecycle.now();
        try {
            String raw = model.generate(request);
            lifecycle.check();
            acceptResult(step, raw);
            plan.accept(step);
            record(step, new Outcome("", ACCEPTED, true, request));
        } catch (InvalidResult failure) {
            String reason = plan.repair(step, failure.feedback);
            record(step, new Outcome(failure.feedback.code(), reason, false, request));
        } finally {
            progress.recordElapsed(step.action(), lifecycle.now() - started);
        }
    }

    private void acceptResult(PlannedStep step, String raw) {
        switch (step.action()) {
            case READ_BATCH -> material.acceptRead(raw, sources.select(step));
            case EDIT_MATERIAL -> material.acceptEdit(raw, step.ids());
            case ORGANIZE_TOPIC -> material.acceptOrganization(raw, step.ids());
            case SELECT_FACTS -> material.acceptSelection(raw, step.ids(), sources.size());
            case WRITE_REPORT -> material.acceptDraft(raw, step.ids(), writing(step));
            default -> throw invariant();
        }
    }

    private DailySummaryMaterial.Writing writing(PlannedStep step) {
        return new DailySummaryMaterial.Writing(
                target(step), plan.isFinalEdit(), sources.forbiddenSpeakers());
    }

    private String prompt(PlannedStep step) {
        var stage =
                new DailySummaryPrompt.Stage(
                        step.action(), step.strategy(), step.failures() > 0, step.feedback());
        if (step.action() == Action.READ_BATCH) {
            return prompts.renderRead(stage, sources.substantive(step));
        }
        DailySummaryPrompt.Material input = material.promptMaterial(step.ids(), sources.ordered());
        if (step.action() == Action.WRITE_REPORT) {
            var draft =
                    new DailySummaryPrompt.Draft(
                            target(step), plan.isFinalEdit(), material.paragraphs());
            return prompts.renderDraft(stage, input, draft);
        }
        return prompts.renderFacts(stage, input);
    }

    private int target(PlannedStep step) {
        int selected = material.selectedCount();
        int proseBudget = DailySummaryReport.MAX_PROSE_CODE_POINTS - selected - 1;
        return Math.max(1, proseBudget * step.ids().size() / Math.max(1, selected));
    }

    private int estimateRead(List<SourceUnit> units) {
        var stage = new DailySummaryPrompt.Stage(Action.READ_BATCH, Strategy.NORMAL, false, null);
        return estimateInput(prompts.renderRead(stage, units));
    }

    private int estimateStep(PlannedStep step) {
        return estimateInput(prompt(step));
    }

    private static int estimateInput(String request) {
        return TokenEstimator.estimateInput(SYSTEM, request);
    }

    private void checkStepLimit() {
        lifecycle.check();
        if (++steps > MAX_STEPS) {
            throw new IllegalStateException("SUMMARY_NO_PROGRESS");
        }
    }

    static IllegalStateException invariant() {
        return new IllegalStateException("SUMMARY_WORKFLOW_INVARIANT");
    }

    private void record(PlannedStep step, Outcome outcome) {
        progress.recordStep(step, outcome, new Counts(calls, steps, plan.replans()));
    }

    /** 返回当前尝试的计数与已记录步骤。 */
    public Diagnostics diagnostics() {
        return progress.snapshot(new Counts(calls, steps, plan.replans()));
    }

    /** 返回已接受的选材审阅快照。 */
    public Map<String, Object> selectionAudit() {
        return material.serializeAudit();
    }

    @FunctionalInterface
    public interface Model {
        String generate(String prompt);
    }

    public record Lifecycle(long deadline, Runnable guard, LongSupplier nanoTime) {
        void check() {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
            if (now() >= deadline) {
                throw new IllegalStateException("SUMMARY_TIMEOUT");
            }
            guard.run();
        }

        long now() {
            return nanoTime.getAsLong();
        }
    }

    public record Audience(SummaryProfile profile, Map<String, String> memberNames) {
        public Audience {
            profile = profile == null ? SummaryProfile.defaults() : profile;
            memberNames = memberNames == null ? Map.of() : Map.copyOf(memberNames);
        }

        /** 返回默认场景和空昵称表。 */
        public static Audience defaults() {
            return new Audience(SummaryProfile.defaults(), Map.of());
        }
    }

    public record Step(
            String action,
            String targetAction,
            String strategy,
            String feedbackCode,
            String changeReason,
            int planVersion,
            int processedUnits,
            int totalUnits,
            int factCount,
            int selectedCount,
            int calls,
            int steps,
            int replans,
            boolean progress,
            String inputFingerprint) {}

    public record Diagnostics(
            int sourceMessages,
            int processedUnits,
            int totalUnits,
            int factCount,
            int criticalCount,
            int selectedCount,
            int criticalCovered,
            int revisionCount,
            Map<String, Integer> omissionReasons,
            int calls,
            int steps,
            int replans,
            int maxRequestEstimate,
            List<Step> trace,
            Map<String, Long> stageMillis) {}

    public record Result(String report, Diagnostics diagnostics) {}

    private static final class Progress {
        private final DailySummarySources sources;
        private final DailySummaryMaterial material;
        private final Consumer<Step> listener;
        private final List<Step> trace = new ArrayList<>();
        private final Map<String, Long> stageMillis = new LinkedHashMap<>();
        private int maxEstimate;

        Progress(
                DailySummarySources sources,
                DailySummaryMaterial material,
                Consumer<Step> listener) {
            this.sources = sources;
            this.material = material;
            this.listener = listener;
        }

        void recordEstimate(int estimate) {
            maxEstimate = Math.max(maxEstimate, estimate);
        }

        void recordElapsed(Action action, long nanos) {
            stageMillis.merge(
                    action.name(), TimeUnit.NANOSECONDS.toMillis(Math.max(0, nanos)), Long::sum);
        }

        void recordStep(PlannedStep plan, Outcome outcome, Counts counts) {
            String action =
                    plan.failures() > 0 ? Action.REPAIR_RESULT.name() : plan.action().name();
            var step =
                    new Step(
                            action,
                            plan.action().name(),
                            plan.strategy().name(),
                            outcome.feedback(),
                            outcome.reason(),
                            1 + counts.replans(),
                            material.processedCount(),
                            sources.size(),
                            material.factCount(),
                            material.selectedCount(),
                            counts.calls(),
                            counts.steps(),
                            counts.replans(),
                            outcome.hasProgress(),
                            fingerprint(outcome.request()));
            trace.add(step);
            listener.accept(step);
        }

        Diagnostics snapshot(Counts counts) {
            return new Diagnostics(
                    sources.messages(),
                    material.processedCount(),
                    sources.size(),
                    material.factCount(),
                    material.criticalCount(),
                    material.selectedCount(),
                    material.criticalCovered(),
                    material.revisionCount(),
                    material.omissionReasons(),
                    counts.calls(),
                    counts.steps(),
                    counts.replans(),
                    maxEstimate,
                    List.copyOf(trace),
                    Map.copyOf(stageMillis));
        }

        private static String fingerprint(String request) {
            if (request == null) {
                return "";
            }
            // 指纹覆盖完整模型请求文本，用于比较同一阶段的输入。
            try {
                byte[] hash =
                        MessageDigest.getInstance("SHA-256")
                                .digest(request.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash);
            } catch (NoSuchAlgorithmException failure) {
                throw new IllegalStateException(
                        "SHA-256 is unavailable for daily-summary input", failure);
            }
        }
    }

    private record Outcome(String feedback, String reason, boolean hasProgress, String request) {}

    private record Counts(int calls, int steps, int replans) {}
}
