/**
 * [INPUT]: 依赖运行时配置、模型预算及 Pi 生命周期边界。
 * [OUTPUT]: 校验生成请求，提供计量生成、原生会话和取消入口。
 * [POS]: Pi 生成与子进程生命周期入口。RPC 帧交换和磁盘材料各自校验。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.pi;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.BotProperties;
import dev.qqbot.MemoryProperties;
import dev.qqbot.model.GenerationObserver;
import dev.qqbot.model.GenerationRequest;
import dev.qqbot.model.PromptPolicy;
import dev.qqbot.model.TextGenerator;
import dev.qqbot.model.TextGenerator.Result;
import dev.qqbot.model.TokenEstimator;
import dev.qqbot.pi.PiExchange.Observation;
import dev.qqbot.pi.PiExchange.Pending;
import dev.qqbot.pi.PiRunRegistry.Run;
import dev.qqbot.trace.Trace;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service("piTextGenerator")
public class PiRuntime implements TextGenerator {
    private static final String CONTEXT_TOO_LARGE = "CONTEXT_TOO_LARGE";
    private final MemoryProperties memory;
    private final int maxTimeoutSeconds;

    /** 使用默认模型窗口构造 Pi 运行时。 */
    public PiRuntime(BotProperties properties, ObjectMapper json) {
        this(properties, json, MemoryProperties.defaults());
    }

    /** 使用部署的模型窗口构造 Pi 运行时；构造时不启动进程。 */
    @Autowired
    public PiRuntime(BotProperties properties, ObjectMapper json, MemoryProperties memory) {
        this(properties, json, memory, new PiRunRegistry());
    }

    PiRuntime(
            BotProperties properties,
            ObjectMapper json,
            MemoryProperties memory,
            PiRunRegistry runs) {
        this.memory = Objects.requireNonNull(memory, "memory must not be null");
        this.maxTimeoutSeconds = properties.pi().maxTimeoutSeconds();
        this.stateRoot = properties.stateDir().toAbsolutePath().normalize();
        this.config = properties.pi();
        this.checkpoints = new PiCheckpoints(stateRoot);
        this.launch = new PiLaunch(config, memory.contextWindow(), json, checkpoints);
        this.exchange = new PiExchange(new PiJsonLines(json), readers);
        this.runs = runs;
    }

    /** 生成计量结果；请求超出模型窗口时在启动进程前拒绝。 */
    @Override
    public Result generate(GenerationRequest request, GenerationObserver observer) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(observer, "observer must not be null");
        requireContextBudget(request);
        return execute(Call.metered(request), observer);
    }

    private void requireContextBudget(GenerationRequest request) {
        int estimated = estimateInput(request);
        long required = (long) estimated + request.maxOutputTokens() + memory.safetyTokens();
        if (estimated > request.maxInputTokens() || required > memory.contextWindow()) {
            throw new IllegalStateException(CONTEXT_TOO_LARGE);
        }
    }

    private static int estimateInput(GenerationRequest request) {
        try {
            return TokenEstimator.estimateInput(request.systemPrompt(), request.userPrompt());
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException(CONTEXT_TOO_LARGE, overflow);
        }
    }

    /** 使用部署的绝对超时运行原生 RPC；持久会话的检查点必须位于状态目录。 */
    public Result generateNative(UUID runId, String prompt, PiSession session) {
        return generateNative(runId, prompt, session, maxTimeoutSeconds);
    }

    /** 使用指定会话和调用超时生成；实际超时不超过部署上限。 */
    public Result generateNative(UUID runId, String prompt, PiSession session, int timeoutSeconds) {
        return execute(
                Call.nativeSession(runId, prompt, session, timeoutSeconds),
                GenerationObserver.noop());
    }

    /** 取消本机运行；不存在的运行不写入子进程。 */
    @Override
    public void cancel(UUID runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        runs.find(runId).ifPresent(run -> requestCancellation(runId, run));
    }

    /** 取消仍在运行的调用，并关闭 RPC 读取线程池。 */
    @PreDestroy
    public void close() {
        for (UUID id : runs.activeIds()) {
            cancel(id);
        }
        readers.shutdownNow();
    }

    private static final int MAX_SLOT_WAIT_SECONDS = 5;
    private static final int GRACEFUL_SHUTDOWN_SECONDS = 2;
    private final Path stateRoot;
    private final BotProperties.Pi config;
    private final PiCheckpoints checkpoints;
    private final PiLaunch launch;
    private final PiExchange exchange;
    private final PiRunRegistry runs;
    private final ExecutorService readers = Executors.newCachedThreadPool();

    private Result execute(Call call, GenerationObserver observer) {
        Observation observation = observe(call, observer);
        Run run = runs.register(call.id());
        try {
            admit(run, call.limits(), observation);
            ProcessBuilder builder = prepareWorkspace(run, call);
            Pending pending = start(builder, run, call, observation);
            Result result = generate(pending, run, call, observation);
            return publishResult(run, call, result, observation.usage());
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException(Run.CANCELLED);
        } catch (IOException | ExecutionException | RuntimeException failure) {
            run.requireActive();
            throw translateFailure(failure);
        } finally {
            reclaim(run, observation.usage());
        }
    }

    private Observation observe(Call call, GenerationObserver observer) {
        int totalSeconds = Math.min(call.limits().timeoutSeconds(), config.maxTimeoutSeconds());
        var budget =
                new GenerationBudget(
                        System.nanoTime(),
                        config.timeoutSeconds(),
                        totalSeconds,
                        call.prompts().userCodePoints());
        return Observation.start(budget, observer);
    }

    private void admit(Run run, Call.Limits limits, Observation observation)
            throws InterruptedException {
        try (var span = Trace.span("pi.admit")) {
            int windowSeconds = Math.min(config.timeoutSeconds(), config.maxTimeoutSeconds());
            int waitSeconds =
                    Math.min(
                            MAX_SLOT_WAIT_SECONDS,
                            Math.min(limits.timeoutSeconds(), windowSeconds));
            runs.acquire(run, limits.isBackground(), waitSeconds);
            observation.requireTimeRemaining();
        }
    }

    private ProcessBuilder prepareWorkspace(Run run, Call call) throws IOException {
        try (var span = Trace.span("pi.workspace")) {
            PiLaunch.Plan plan = launch.planLaunch(call);
            // SIDE EFFECT: 登记临时目录后再写材料，确保所有启动错误都能回收。
            Files.createDirectories(stateRoot);
            Path root = Files.createTempDirectory(stateRoot, "run-");
            run.trackWorkspace(root);
            return launch.prepareWorkspace(
                    PiLaunch.Workspace.at(root), plan, call.prompts().system());
        }
    }

    private Pending start(ProcessBuilder builder, Run run, Call call, Observation observation)
            throws IOException, InterruptedException, ExecutionException {
        observation.requireTimeRemaining();
        try (var span = Trace.span("pi.spawn")) {
            // SIDE EFFECT: 立即公布进程，以便确认输出限额之前也能取消。
            Process child = builder.start();
            run.trackChild(child);
            run.requireActive();
            Pending pending = exchange.begin(child, call, observation);
            run.requireActive();
            observation.requireTimeRemaining();
            return pending;
        }
    }

    private Result generate(Pending pending, Run run, Call call, Observation observation)
            throws IOException, InterruptedException, ExecutionException {
        try (var span = Trace.span("pi.generate")) {
            PiExchange.Generated generated = exchange.generate(pending, call, observation);
            run.requireActive();
            observation.usage().describe(span, generated.promptNanos());
            return generated.result();
        }
    }

    private Result publishResult(Run run, Call call, Result answer, PiUsageCapture usage)
            throws IOException, InterruptedException {
        try (var span = Trace.span("pi.shutdown")) {
            Process child = run.findChild().orElseThrow(() -> missingResource(call, "process"));
            Path workspace =
                    run.findWorkspace().orElseThrow(() -> missingResource(call, "workspace"));
            // SIDE EFFECT: 先等待会话写完，再从本次工作目录发布检查点。
            child.getOutputStream().close();
            if (!child.waitFor(GRACEFUL_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                ProcessResources.stop(child);
            }
            String checkpoint =
                    call.session().isPersistent()
                            ? checkpoints.publish(call.id(), workspace, answer.checkpoint())
                            : null;
            return new Result(answer.text(), checkpoint, usage.value);
        }
    }

    private static IllegalStateException missingResource(Call call, String resource) {
        return new IllegalStateException("Missing Pi " + resource + ": runId=" + call.id());
    }

    private static RuntimeException translateFailure(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof CancellationException cancelled) {
            return cancelled;
        }
        if (cause instanceof IllegalStateException state) {
            return state;
        }
        return new IllegalStateException("PI_FAILED", cause);
    }

    private void reclaim(Run run, PiUsageCapture usage) {
        try (var span = Trace.span("pi.teardown")) {
            try {
                run.findChild().ifPresent(ProcessResources::stop);
            } finally {
                releaseWorkspace(run, usage);
            }
        }
    }

    private void releaseWorkspace(Run run, PiUsageCapture usage) {
        runs.unregister(run);
        try {
            run.findWorkspace().ifPresent(ProcessResources::deleteWorkspace);
        } finally {
            runs.release(run);
            usage.finish();
        }
    }

    private void requestCancellation(UUID id, Run run) {
        run.requestCancellation();
        run.findChild().ifPresent(child -> exchange.abort(child, id));
    }

    record Call(UUID id, Prompts prompts, PiSession session, Limits limits) {
        Call {
            Objects.requireNonNull(id, "runId must not be null");
            Objects.requireNonNull(prompts, "prompts must not be null");
            Objects.requireNonNull(session, "session must not be null");
            Objects.requireNonNull(limits, "limits must not be null");
        }

        static Call metered(GenerationRequest request) {
            var prompts = new Prompts(request.systemPrompt(), request.userPrompt());
            var limits =
                    new Limits(
                            request.timeoutSeconds(),
                            request.maxOutputTokens(),
                            request.purpose().background());
            return new Call(request.runId(), prompts, PiSession.ephemeral(), limits);
        }

        static Call nativeSession(UUID id, String prompt, PiSession session, int timeoutSeconds) {
            var prompts = new Prompts(PromptPolicy.systemPrompt(), prompt);
            return new Call(id, prompts, session, new Limits(timeoutSeconds, null, false));
        }

        record Prompts(String system, String user) {
            Prompts {
                Objects.requireNonNull(system, "systemPrompt must not be null");
                Objects.requireNonNull(user, "userPrompt must not be null");
            }

            int userCodePoints() {
                return user.codePointCount(0, user.length());
            }
        }

        record Limits(int timeoutSeconds, Integer outputTokens, boolean isBackground) {
            boolean hasOutputLimit() {
                return outputTokens != null;
            }
        }
    }
}
