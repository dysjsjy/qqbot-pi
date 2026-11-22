/**
 * [INPUT]: 依赖日报认领与发布服务、生成边界和 SQLite 作业状态。
 * [OUTPUT]: 串行生成待执行日报，记录进度、失败与分阶段耗时。
 * [POS]: 日报后台执行器。仅在认领事务提交后启动生成。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import dev.qqbot.MemoryProperties;
import dev.qqbot.model.TextGenerator;
import dev.qqbot.persistence.Db;
import dev.qqbot.schedule.DailySummaryGeneration.Budget;
import dev.qqbot.schedule.DailySummaryService.Snapshot;
import dev.qqbot.schedule.DailySummaryWorkflow.Step;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DailySummaryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(DailySummaryWorker.class);
    private static final long GENERATION_DEADLINE_NANOS = Duration.ofMinutes(30).toNanos();
    private static final Set<String> FAILURE_CODES =
            Set.of(
                    "CALL_LIMIT",
                    "SUMMARY_TIMEOUT",
                    "CONTEXT_TOO_LARGE",
                    "SOURCE_SNAPSHOT_EXPIRED",
                    "INVALID_CITATION",
                    "MISSING_CITATION",
                    "EMPTY_REPORT",
                    "SUMMARY_NO_PROGRESS",
                    "SUMMARY_RESULT_INVALID",
                    "SUMMARY_REQUIRED_FACT_MISSING",
                    "SUMMARY_WORKFLOW_INVARIANT",
                    "MODEL_FAILED",
                    "MODEL_TIMEOUT",
                    "REPORT_TOO_LONG",
                    "REPORT_PRESENTATION_INVALID",
                    "REPORT_SENTENCE_TOO_LONG",
                    "REPORT_TOO_MANY_PARTS");
    private static final String STEP_LOG =
            "SUMMARY_STEP run={} action={} target={} strategy={} feedback={} "
                    + "change={} plan={} processed={}/{} facts={} selected={} calls={} steps={} replans={} progress={}";

    private final Db db;
    private final DailySummaryService service;
    private final TextGenerator generator;
    private final MemoryProperties budgets;
    private final DailySummaryGeneration generation;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile UUID active;
    private volatile DailySummaryWorkflow.Diagnostics diagnostics;
    private volatile Map<String, Object> selectionAudit = Map.of();
    private volatile Timing timing;

    /** 创建使用默认生成预算的日报执行器。 */
    public DailySummaryWorker(Db db, DailySummaryService service, TextGenerator pi) {
        this(db, service, pi, MemoryProperties.defaults());
    }

    /** 创建共享同一模型端口和预算的日报执行器。 */
    @Autowired
    public DailySummaryWorker(
            Db db, DailySummaryService service, TextGenerator pi, MemoryProperties budgets) {
        this.db = db;
        this.service = service;
        this.generator = pi;
        this.budgets = budgets;
        this.generation = new DailySummaryGeneration(db, pi, budgets);
    }

    /** 在执行器空闲时取最早作业。已失效的执行请求取消模型。 */
    @Scheduled(fixedDelay = 500)
    public synchronized void tick() {
        UUID running = active;
        if (running != null) {
            cancelIfInactive(running);
            return;
        }
        db.find(
                        "select id from daily_run where state='QUEUED' order by created_at limit 1",
                        (row, number) -> UUID.fromString(row.getString(1)))
                .ifPresent(this::submit);
    }

    private void submit(UUID id) {
        active = id;
        executor.submit(() -> run(id));
    }

    private void cancelIfInactive(UUID id) {
        boolean isActive =
                db.find(
                                "select id from daily_run where id=? and state in ('RUNNING','QUEUED')",
                                (row, number) -> true,
                                id)
                        .isPresent();
        if (!isActive) {
            generator.cancel(id);
        }
    }

    private void run(UUID id) {
        clearDiagnostics();
        var attempt = new Attempt(System.nanoTime());
        try {
            service.claim(id).ifPresent(snapshot -> runClaimed(snapshot, attempt));
        } catch (RuntimeException failure) {
            failRun(id, failure);
        } finally {
            recordDiagnostics(id, attempt);
            active = null;
        }
    }

    private void clearDiagnostics() {
        diagnostics = null;
        selectionAudit = Map.of();
        timing = null;
    }

    private void runClaimed(Snapshot snapshot, Attempt attempt) {
        var header = snapshot.header();
        UUID id = header.id();
        LOG.info("SUMMARY_START run={}", id);
        attempt.queueMs = readQueueMs(id);
        attempt.workflow = createWorkflow(snapshot, attempt.budget.deadline());
        String report =
                attempt.workflow
                        .execute(
                                snapshot.material().messages(),
                                prompt -> generation.generate(header, prompt, attempt.budget))
                        .report();
        // SIDE EFFECT: 发布服务在同一事务写入日报和投递分段。
        service.finish(snapshot, report);
        verifyPublished(id);
        LOG.info("SUMMARY_GENERATED run={} group={}", id, header.group());
    }

    private DailySummaryWorkflow createWorkflow(Snapshot snapshot, long deadline) {
        var lifecycle =
                new DailySummaryWorkflow.Lifecycle(
                        deadline,
                        () -> generation.ensureActive(snapshot.header()),
                        System::nanoTime);
        var audience =
                new DailySummaryWorkflow.Audience(
                        snapshot.coverage().profile(), snapshot.material().memberNames());
        return new DailySummaryWorkflow(
                budgets, lifecycle, step -> logStep(snapshot.header().id(), step), audience);
    }

    private static void logStep(UUID id, Step step) {
        LOG.info(
                STEP_LOG,
                id,
                step.action(),
                step.targetAction(),
                step.strategy(),
                step.feedbackCode(),
                step.changeReason(),
                step.planVersion(),
                step.processedUnits(),
                step.totalUnits(),
                step.factCount(),
                step.selectedCount(),
                step.calls(),
                step.steps(),
                step.replans(),
                step.progress());
    }

    private long readQueueMs(UUID id) {
        return db.find(
                        """
                select coalesce((select max(created_at) from admin_audit where action='SUMMARY_RETRY' and target=?),
                  created_at) from daily_run where id=?
                """,
                        (row, number) -> row.getLong(1),
                        id.toString(),
                        id)
                .map(queuedAt -> Math.max(0, System.currentTimeMillis() - queuedAt))
                .orElse(0L);
    }

    private void verifyPublished(UUID id) {
        boolean isPublished =
                db.find(
                                """
                select id from daily_run where id=?
                  and state in ('GENERATED','DELIVERED','DELIVERY_FAILED','DELIVERY_UNKNOWN')
                """,
                                (row, number) -> true,
                                id)
                        .isPresent();
        if (!isPublished) {
            throw new CancellationException();
        }
    }

    private void failRun(UUID id, RuntimeException failure) {
        String code = errorCode(failure);
        LOG.warn("SUMMARY_FAILED run={} code={}", id, code);
        db.exec(
                """
                update daily_run set state=?,error_code=?,finished_at=cast(unixepoch('subsec')*1000 as integer)
                where id=? and state in ('QUEUED','RUNNING')
                """,
                failure instanceof CancellationException ? "CANCELLED" : "FAILED",
                code,
                id);
    }

    private static String errorCode(RuntimeException failure) {
        if (failure instanceof CancellationException) {
            return "SUMMARY_CANCELLED";
        }
        String code = failure.getMessage();
        return failure instanceof IllegalStateException
                        && code != null
                        && FAILURE_CODES.contains(code)
                ? code
                : "SUMMARY_FAILED";
    }

    private void recordDiagnostics(UUID id, Attempt attempt) {
        if (attempt.workflow != null) {
            diagnostics = attempt.workflow.diagnostics();
            selectionAudit = attempt.workflow.selectionAudit();
        }
        Timing result = attempt.finish();
        timing = result;
        LOG.info(
                "SUMMARY_TIMING run={} queueMs={} generationMs={} backgroundWaitMs={} calls={}",
                id,
                result.queueMs(),
                result.generationMs(),
                result.backgroundWaitMs(),
                result.dispatchedCalls());
    }

    /** 返回上一次生成尝试的工作流诊断。 */
    public DailySummaryWorkflow.Diagnostics diagnostics() {
        return diagnostics;
    }

    /** 返回上一次执行的排队与生成时间。 */
    public Timing timing() {
        return timing;
    }

    /** 返回上一次生成尝试的选材审阅结果。 */
    public Map<String, Object> selectionAudit() {
        return selectionAudit;
    }

    /** 取消当前模型请求并停止后台执行器。 */
    @PreDestroy
    public void close() {
        UUID running = active;
        if (running != null) {
            generator.cancel(running);
        }
        executor.shutdownNow();
    }

    public record Timing(
            long queueMs, long generationMs, long backgroundWaitMs, int dispatchedCalls) {}

    private static final class Attempt {
        private final long started;
        private final Budget budget;
        private long queueMs;
        private DailySummaryWorkflow workflow;

        Attempt(long started) {
            this.started = started;
            this.budget = new Budget(started + GENERATION_DEADLINE_NANOS);
        }

        Timing finish() {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            return new Timing(
                    queueMs, elapsed, budget.backgroundWaitMs(), budget.dispatchedCalls());
        }
    }
}
