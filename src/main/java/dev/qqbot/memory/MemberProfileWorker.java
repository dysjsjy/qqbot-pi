/**
 * [INPUT]: 依赖群画像策略、作业认领、抽取编排、生成端口和调用链。
 * [OUTPUT]: 限额调度抽取、重放与重铸；恢复中断作业。
 * [POS]: 实例锁与账目恢复后启动；和日报共用模型后台槽，不阻塞聊天。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.memory.MemberProfileExtractor.Work;
import dev.qqbot.model.TextGenerator;
import dev.qqbot.persistence.Db;
import dev.qqbot.trace.Trace;
import dev.qqbot.trace.TraceSink;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@DependsOn("memoryUsageLifecycle")
public class MemberProfileWorker {
    private static final Logger LOG = LoggerFactory.getLogger(MemberProfileWorker.class);
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,79}");
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;
    private final Clock clock;
    private final MemberProfilePolicyService policies;
    private final MemberProfileJobs jobs;
    private final MemberProfileExtractor extractor;
    private final TextGenerator generator;
    private final TraceSink traces;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ReentrantLock workerLock = new ReentrantLock();
    private Future<?> polling;
    private volatile UUID active;

    /** 建立单实例画像调度器。作业认领和执行由同一把锁串行化。 */
    public MemberProfileWorker(
            Db db,
            Clock clock,
            MemberProfilePolicyService policies,
            MemberProfileStats stats,
            MemberProfileService service,
            MemberProfileExtractor extractor,
            TextGenerator generator,
            TraceSink traces) {
        this.clock = clock;
        this.policies = policies;
        this.jobs = new MemberProfileJobs(db, stats, service, extractor);
        this.extractor = extractor;
        this.generator = generator;
        this.traces = traces;
    }

    @PostConstruct
    /** 将上次进程退出时仍在执行的作业标记为失败。 */
    public void recover() {
        jobs.recover(clock.instant());
    }

    /** 全局开关开启且上次轮询结束后提交一次轮询。 */
    @Scheduled(fixedDelayString = "${bot.experimental.member-profile.poll-ms:30000}")
    public synchronized void tick() {
        if (!policies.globallyEnabled() || polling != null && !polling.isDone()) {
            return;
        }
        polling = executor.submit(this::pollScheduled);
    }

    private void pollScheduled() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            LOG.warn(
                    "MEMBER_PROFILE_WORKER_FAILED exception={}",
                    failure.getClass().getSimpleName());
        }
    }

    /** 同步尝试执行一份作业。开关关闭、正在轮询或无可执行作业时返回 false。 */
    public boolean runOnce() {
        if (!policies.globallyEnabled() || !workerLock.tryLock()) {
            return false;
        }
        try {
            return poll();
        } finally {
            workerLock.unlock();
        }
    }

    private boolean poll() {
        Instant now = clock.instant();
        var groups = jobs.readEnabledGroups();
        jobs.pruneScans(groups);
        for (UUID group : groups) {
            var work = jobs.claim(group, now, policies);
            if (work.isPresent()) {
                work.ifPresent(this::execute);
                return true;
            }
        }
        return false;
    }

    private void execute(Work work) {
        active = work.id();
        Trace.begin(traces, Trace.BACKGROUND, "profile.job", work.id(), work.scope().group());
        String status = Trace.OK;
        String error = null;
        try {
            Trace.attr("jobType", work.job().type().name());
            extractor.execute(work);
        } catch (RuntimeException failure) {
            error = errorCode(failure);
            boolean isDeferred = MemberProfileJobs.isDeferred(error);
            boolean isCancelled = failure instanceof CancellationException;
            status = isDeferred || isCancelled ? Trace.CANCELLED : Trace.ERROR;
            recordFailure(work.id(), error, isDeferred, isCancelled);
        } finally {
            Trace.end(status, error);
            active = null;
        }
    }

    private static String errorCode(RuntimeException failure) {
        String message = failure.getMessage();
        return message != null && ERROR_CODE.matcher(message).matches()
                ? message
                : "PROFILE_JOB_FAILED";
    }

    private void recordFailure(UUID id, String error, boolean isDeferred, boolean isCancelled) {
        // SIDE EFFECT: 暂时无法分发的作业重新排队，其余作业记录终态。
        if (isDeferred) {
            jobs.requeue(id, error);
        } else {
            jobs.finish(id, error, clock.instant(), isCancelled);
        }
    }

    /** 取消当前模型调用，最多等待五秒释放调度线程。 */
    @PreDestroy
    public void close() {
        UUID job = active;
        if (job != null) {
            generator.cancel(job);
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
