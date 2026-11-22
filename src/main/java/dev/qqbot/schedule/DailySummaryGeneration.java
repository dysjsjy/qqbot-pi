/**
 * [INPUT]: 依赖有效作业快照、模型端口、生成预算和运行时状态。
 * [OUTPUT]: 限额生成单个日报步骤，累计分发次数和槽位等待。
 * [POS]: 日报模型调用边界。每次重试重新核对作业和时间预算。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import dev.qqbot.MemoryProperties;
import dev.qqbot.model.GenerationObserver;
import dev.qqbot.model.GenerationRequest;
import dev.qqbot.model.ModelUsage;
import dev.qqbot.model.PromptPolicy;
import dev.qqbot.model.Purpose;
import dev.qqbot.model.TextGenerator;
import dev.qqbot.model.TokenEstimator;
import dev.qqbot.persistence.Db;
import dev.qqbot.schedule.DailySummaryService.Snapshot.Header;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DailySummaryGeneration {
    private static final int MAX_CALLS = 40;
    private static final int MAX_TRANSIENT_RETRIES = 2;
    private static final long SLOT_WAIT_NANOS = Duration.ofMillis(100).toNanos();
    private static final long TRANSIENT_RETRY_NANOS = Duration.ofMillis(150).toNanos();
    private static final String SYSTEM = PromptPolicy.systemPrompt(Purpose.DAILY_SUMMARY);
    private static final Logger LOG = LoggerFactory.getLogger(DailySummaryWorker.class);
    private final Db db;
    private final TextGenerator generator;
    private final MemoryProperties budgets;

    DailySummaryGeneration(Db db, TextGenerator generator, MemoryProperties budgets) {
        this.db = db;
        this.generator = generator;
        this.budgets = budgets;
    }

    void ensureActive(Header header) {
        boolean isActive =
                db.find(
                                """
                select r.id from daily_run r join group_config g on g.id=r.group_id and g.epoch=r.epoch
                where r.id=? and r.group_id=? and r.epoch=? and r.state='RUNNING' and g.enabled and g.reply_enabled
                """,
                                (row, number) -> true,
                                header.id(),
                                header.group(),
                                header.epoch())
                        .isPresent();
        if (!isActive) {
            throw new CancellationException();
        }
    }

    String generate(Header header, String prompt, Budget budget) {
        int transientFailures = 0;
        while (true) {
            GenerationRequest request = buildRequest(header, prompt, budget);
            var call = new Call(budget);
            long started = System.nanoTime();
            try {
                // SIDE EFFECT: 计量端口在分发前为本次模型调用落账。
                return generator.generate(request, call).text();
            } catch (IllegalStateException failure) {
                if (isWaitingForSlot(call, failure)) {
                    waitForSlot(budget, started);
                    continue;
                }
                if (!canRetry(call, failure, budget, transientFailures)) {
                    throw failure;
                }
                transientFailures++;
                waitToRetry(header.id(), transientFailures, failure.getMessage());
            }
        }
    }

    private GenerationRequest buildRequest(Header header, String prompt, Budget budget) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
        if (budget.dispatched >= MAX_CALLS) {
            throw new IllegalStateException("CALL_LIMIT");
        }
        long remaining = TimeUnit.NANOSECONDS.toSeconds(budget.deadline - System.nanoTime());
        if (remaining < 1) {
            throw new IllegalStateException("SUMMARY_TIMEOUT");
        }
        ensureActive(header);
        if (TokenEstimator.estimateInput(SYSTEM, prompt) > budgets.inputTokens()) {
            throw new IllegalStateException("CONTEXT_TOO_LARGE");
        }
        return new GenerationRequest(
                header.id(),
                UUID.randomUUID(),
                header.group(),
                header.epoch(),
                Purpose.DAILY_SUMMARY,
                SYSTEM,
                prompt,
                budgets.inputTokens(),
                budgets.summaryOutputTokens(),
                (int) remaining);
    }

    private static boolean isWaitingForSlot(Call call, IllegalStateException failure) {
        return !call.isDispatched && "BACKGROUND_BUSY".equals(failure.getMessage());
    }

    private static boolean canRetry(
            Call call, IllegalStateException failure, Budget budget, int failures) {
        String code = failure.getMessage();
        return call.isDispatched
                && ("PI_FAILED".equals(code) || "MODEL_FAILED".equals(code))
                && failures < MAX_TRANSIENT_RETRIES
                && budget.dispatched < MAX_CALLS;
    }

    private static void waitForSlot(Budget budget, long started) {
        LockSupport.parkNanos(SLOT_WAIT_NANOS);
        budget.slotWait += System.nanoTime() - started;
    }

    private static void waitToRetry(UUID id, int attempt, String code) {
        LOG.warn("SUMMARY_CALL_RETRY run={} attempt={} code={}", id, attempt, code);
        LockSupport.parkNanos(TRANSIENT_RETRY_NANOS * attempt);
    }

    static final class Budget {
        private final long deadline;
        private int dispatched;
        private long slotWait;

        Budget(long deadline) {
            this.deadline = deadline;
        }

        long deadline() {
            return deadline;
        }

        int dispatchedCalls() {
            return dispatched;
        }

        long backgroundWaitMs() {
            return TimeUnit.NANOSECONDS.toMillis(slotWait);
        }
    }

    private static final class Call implements GenerationObserver {
        private final Budget budget;
        private boolean isDispatched;

        Call(Budget budget) {
            this.budget = budget;
        }

        @Override
        public void dispatched() {
            if (!isDispatched) {
                isDispatched = true;
                budget.dispatched++;
            }
        }

        @Override
        public void usage(ModelUsage value) {}
    }
}
