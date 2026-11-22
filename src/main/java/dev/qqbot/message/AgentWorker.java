/**
 * [INPUT]: 依赖回复任务存储、记忆上下文、生成端口、预算和时钟。
 * [OUTPUT]: 认领任务、构建上下文、生成与验收答复。
 * [POS]: 单任务回复编排器。状态与回执由 AgentRunStore 提交。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.message;

import dev.qqbot.BotProperties;
import dev.qqbot.MemoryProperties;
import dev.qqbot.memory.*;
import dev.qqbot.message.AgentRunStore.Job;
import dev.qqbot.model.*;
import dev.qqbot.persistence.Db;
import dev.qqbot.text.Texts;
import dev.qqbot.trace.*;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AgentWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AgentWorker.class);
    private static final int DEFAULT_QUEUE_TIMEOUT_SECONDS = 120;
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 420;
    private static final String CONTEXT_TOO_LARGE = "CONTEXT_TOO_LARGE";
    private static final String EMPTY_ANSWER = "EMPTY_ANSWER";
    private static final String REQUEST_TIMEOUT = "REQUEST_TIMEOUT";
    private static final String SOURCE_MISSING = "SOURCE_MISSING";
    private static final Set<String> GENERATION_ERRORS =
            Set.of(
                    "PI_FIRST_CONTENT_TIMEOUT",
                    "PI_IDLE_TIMEOUT",
                    "PI_DEADLINE",
                    REQUEST_TIMEOUT,
                    EMPTY_ANSWER,
                    SOURCE_MISSING);
    private final Db db;
    private final AgentRunStore jobs;
    private final TextGenerator generator;
    private final MemoryService memory;
    private final int queueTimeoutSeconds;
    private final int requestTimeoutSeconds;
    private final int outputTokens;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile UUID active;
    private TraceSink traces;

    /** 配置可选的链路记录器。 */
    @Autowired(required = false)
    public void setTraces(TraceSink traces) {
        this.traces = traces;
    }

    public AgentWorker(Db db, TextGenerator generator, MemoryService memory) {
        this(db, generator, memory, (BotProperties) null);
    }

    public AgentWorker(
            Db db, TextGenerator generator, MemoryService memory, BotProperties properties) {
        this(
                db,
                generator,
                memory,
                new Settings(properties, MemoryProperties.defaults(), Clock.systemUTC()));
    }

    @Autowired
    public AgentWorker(Db db, TextGenerator generator, MemoryService memory, Settings settings) {
        this.db = db;
        this.jobs = new AgentRunStore(db);
        this.generator = generator;
        this.memory = memory;
        this.queueTimeoutSeconds = settings.queueTimeoutSeconds();
        this.requestTimeoutSeconds = settings.requestTimeoutSeconds();
        this.outputTokens = settings.budgets().outputTokens();
        this.clock = settings.clock();
    }

    /** 回收过期任务，并在执行槽空闲时认领下一个任务。 */
    @Scheduled(fixedDelayString = "${bot.worker.poll-ms:200}")
    public synchronized void tick() {
        expireQueuedJobs();
        UUID running = active;
        if (running != null) {
            if (!jobs.isRunning(running)) {
                generator.cancel(running);
            }
            return;
        }
        claim().ifPresent(this::submit);
    }

    private void expireQueuedJobs() {
        Instant cutoff = clock.instant().minusSeconds(queueTimeoutSeconds);
        for (Job expired : jobs.queuedBefore(cutoff)) {
            jobs.expire(expired);
        }
    }

    private void submit(Job job) {
        active = job.id();
        executor.submit(() -> run(job));
    }

    private void run(Job job) {
        startTrace(job);
        var outcome = new Outcome(Trace.OK, null);
        LOG.info("AGENT_START run={} group={}", job.id(), job.route().groupId());
        try {
            answer(job);
        } catch (CancellationException cancelled) {
            outcome = new Outcome(Trace.CANCELLED, AgentRunStore.STALE_RESULT);
            jobs.cancel(job);
        } catch (RuntimeException failure) {
            outcome = new Outcome(Trace.ERROR, failureCode(failure));
            recordFailure(job, outcome.code(), failure);
        } finally {
            Trace.end(outcome.status(), outcome.code());
            active = null;
        }
    }

    private void startTrace(Job job) {
        Trace.begin(traces, Trace.AGENT, "agent.run", job.id(), job.route().groupId());
        Trace.attr("runKind", job.input().kind());
        if (job.queuedAt() != null) {
            Trace.attr("queueWaitMs", Math.max(0, clock.millis() - job.queuedAt().toEpochMilli()));
        }
    }

    private void answer(Job job) {
        jobs.requireCurrent(job);
        ContextRequest request = buildContextRequest(job);
        ContextBundle context = memory.assemble(request);
        recordContext(job, context);
        if (request.mode() == QueryMode.HISTORY && context.evidence().isEmpty()) {
            complete(job, "没有找到当前范围内的相关记录。", null);
            return;
        }
        TextGenerator.Result result = generate(job, context, request.mode());
        jobs.complete(job, validatedAnswer(result, context));
    }

    private ContextRequest buildContextRequest(Job job) {
        var route = job.route();
        var input = job.input();
        var trigger = input.trigger();
        if (trigger.beforeSeq() == null || trigger.occurredAt() == null) {
            throw new IllegalStateException(SOURCE_MISSING);
        }
        String owner = jobs.requireOwner(job);
        var mode = QueryRouter.route(input.kind(), input.prompt());
        var scope =
                new MemoryScope(
                        route.groupId(),
                        route.epoch(),
                        trigger.messageId(),
                        trigger.beforeSeq(),
                        trigger.occurredAt(),
                        clock.instant());
        return new ContextRequest(
                scope, route.conversationId(), route.generation(), owner, input.prompt(), mode);
    }

    private void recordContext(Job job, ContextBundle context) {
        try (var span = Trace.span("agent.stats")) {
            db.tx(() -> recordCurrentContext(job, context));
        }
    }

    private Void recordCurrentContext(Job job, ContextBundle context) {
        jobs.requireCurrent(job);
        memory.recordStats(job.route().groupId(), job.id(), context);
        return null;
    }

    private TextGenerator.Result generate(Job job, ContextBundle context, QueryMode mode) {
        int inputLimit = memory.inputLimit();
        jobs.requireCurrent(job);
        int timeout = remainingSeconds(job.queuedAt(), clock.instant(), requestTimeoutSeconds);
        var request =
                new GenerationRequest(
                        job.id(),
                        UUID.randomUUID(),
                        job.route().groupId(),
                        job.route().epoch(),
                        mode == QueryMode.HISTORY ? Purpose.MEMORY_QUERY : Purpose.CHAT,
                        context.systemPrompt(),
                        context.userPrompt(),
                        inputLimit,
                        outputTokens,
                        timeout);
        // SIDE EFFECT: 调用生成端口前已复核作业范围和截止时间。
        return generator.generate(request, GenerationObserver.noop());
    }

    static int remainingSeconds(Instant queuedAt, Instant now, int timeoutSeconds) {
        long remaining = Duration.between(now, queuedAt.plusSeconds(timeoutSeconds)).toSeconds();
        if (remaining < 1) {
            throw new IllegalStateException(REQUEST_TIMEOUT);
        }
        return (int) Math.min(timeoutSeconds, remaining);
    }

    private static AgentRunStore.Answer validatedAnswer(
            TextGenerator.Result result, ContextBundle context) {
        String display = requireDisplay(result.text());
        if (!CitationValidator.accepts(
                display, context.citationMode(), context.evidence().size())) {
            display = "资料引用不完整，请换个问法或查看原始记录。";
        }
        return prepareAnswer(display, result.checkpoint());
    }

    private static String requireDisplay(String answer) {
        String display = Texts.plainText(answer);
        if (display.isBlank()) {
            throw new IllegalStateException(EMPTY_ANSWER);
        }
        return display;
    }

    private static AgentRunStore.Answer prepareAnswer(String display, String checkpoint) {
        return new AgentRunStore.Answer(display, checkpoint, Texts.splitDelivery(display));
    }

    private static String failureCode(RuntimeException failure) {
        String code = failure.getMessage();
        if (CONTEXT_TOO_LARGE.equals(code)) {
            return CONTEXT_TOO_LARGE;
        }
        return failure instanceof IllegalStateException
                        && code != null
                        && GENERATION_ERRORS.contains(code)
                ? code
                : "AGENT_FAILED";
    }

    private void recordFailure(Job job, String code, RuntimeException failure) {
        if (CONTEXT_TOO_LARGE.equals(code)) {
            complete(job, "当前问题超出上下文限制，请缩短后重试。", null);
            return;
        }
        LOG.warn(
                "AGENT_FAILED run={} code={} exception={}",
                job.id(),
                code,
                failure.getClass().getSimpleName());
        jobs.fail(job, code);
    }

    /** 原子认领一个当前代际的回复任务。 */
    public Optional<Job> claim() {
        return jobs.claim();
    }

    /** 格式化并提交已认领任务的答复。正文为空时抛出异常。 */
    public void complete(Job job, String answer, String checkpoint) {
        Objects.requireNonNull(job, "job must not be null");
        jobs.complete(job, prepareAnswer(requireDisplay(answer), checkpoint));
    }

    /** 取消当前运行并停止执行线程。 */
    @PreDestroy
    public void close() {
        UUID running = active;
        if (running != null) {
            generator.cancel(running);
        }
        executor.shutdownNow();
    }

    @Component
    public record Settings(BotProperties properties, MemoryProperties budgets, Clock clock) {
        int queueTimeoutSeconds() {
            return properties == null
                    ? DEFAULT_QUEUE_TIMEOUT_SECONDS
                    : properties.pi().queueTimeoutSeconds();
        }

        int requestTimeoutSeconds() {
            return properties == null
                    ? DEFAULT_REQUEST_TIMEOUT_SECONDS
                    : properties.pi().requestTimeoutSeconds();
        }
    }

    private record Outcome(String status, String code) {}
}
