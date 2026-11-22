/**
 * [INPUT]: 接收已启动的子进程、冻结调用和有界时间预算。
 * [OUTPUT]: 等待输出限额确认，分发提示，读取最终答复与会话位置。
 * [POS]: 单次运行的 RPC 收发边界；不分配执行槽或清理工作目录。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.pi;

import dev.qqbot.model.GenerationObserver;
import dev.qqbot.model.TextGenerator.Result;
import dev.qqbot.pi.PiJsonLines.Answer;
import dev.qqbot.pi.PiJsonLines.Event;
import dev.qqbot.pi.PiJsonLines.Progress;
import dev.qqbot.pi.PiJsonLines.Ready;
import dev.qqbot.pi.PiJsonLines.Resources;
import dev.qqbot.pi.PiJsonLines.Response;
import dev.qqbot.pi.PiJsonLines.Settled;
import dev.qqbot.pi.PiRuntime.Call;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PiExchange {
    private static final Logger LOG = LoggerFactory.getLogger(PiExchange.class);
    private static final String ID = "id";
    private static final String TYPE = "type";
    private static final int MAX_READY_WAIT_SECONDS = 5;
    private static final int RESULT_POLL_MILLIS = 100;
    private final PiJsonLines rpc;
    private final ExecutorService readers;

    PiExchange(PiJsonLines rpc, ExecutorService readers) {
        this.rpc = rpc;
        this.readers = readers;
    }

    Pending begin(Process child, Call call, Observation observation)
            throws InterruptedException, ExecutionException {
        var reply = new Reply(child, call, observation);
        Call.Limits limits = call.limits();
        // SIDE EFFECT: 后台读取两条管道，等待进程报告的输出上限。
        readers.submit(() -> drainErrors(child));
        var answer = CompletableFuture.supplyAsync(reply::read, readers);
        if (limits.hasOutputLimit()) {
            awaitReady(reply.ready, answer, limits.timeoutSeconds());
        }
        return new Pending(child, answer);
    }

    private static void drainErrors(Process child) {
        try (var errors = child.getErrorStream()) {
            errors.transferTo(OutputStream.nullOutputStream());
        } catch (IOException unavailable) {
            LOG.debug("Pi stderr closed: pid={}", child.pid());
        }
    }

    private static void awaitReady(
            CompletableFuture<Void> ready, CompletableFuture<Result> answer, int timeoutSeconds)
            throws InterruptedException, ExecutionException {
        answer.whenComplete((result, failure) -> forwardReadFailure(ready, failure));
        try {
            ready.get(Math.min(MAX_READY_WAIT_SECONDS, timeoutSeconds), TimeUnit.SECONDS);
        } catch (TimeoutException failure) {
            throw new IllegalStateException("PI_OUTPUT_LIMIT_UNSUPPORTED", failure);
        }
    }

    private static void forwardReadFailure(CompletableFuture<Void> ready, Throwable failure) {
        if (failure != null) {
            ready.completeExceptionally(failure);
        }
    }

    Generated generate(Pending pending, Call call, Observation observation)
            throws IOException, InterruptedException, ExecutionException {
        var command =
                Map.of(ID, call.id().toString(), TYPE, "prompt", "message", call.prompts().user());
        long promptNanos = System.nanoTime();
        // SIDE EFFECT: 观察者先记下分发，再写入子进程的提示帧。
        observation.observer().dispatched();
        rpc.send(pending.child(), command);
        Result result = awaitResult(pending.answer(), observation);
        observation.requireTimeRemaining();
        return new Generated(result, promptNanos);
    }

    private static Result awaitResult(CompletableFuture<Result> answer, Observation observation)
            throws InterruptedException, ExecutionException {
        while (true) {
            observation.requireTimeRemaining();
            try {
                return answer.get(RESULT_POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException pending) {
                observation.requireTimeRemaining();
            }
        }
    }

    void abort(Process child, UUID runId) {
        try {
            rpc.send(child, Map.of(ID, "cancel-" + runId, TYPE, "abort"));
        } catch (IOException unavailable) {
            LOG.debug("Pi abort pipe unavailable: runId={}", runId);
        }
        ProcessResources.stop(child);
    }

    private final class Reply {
        private final Process child;
        private final Call call;
        private final Observation observation;
        private final String promptId;
        private final String stateId;
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private String finalText;
        private boolean isAccepted;

        Reply(Process child, Call call, Observation observation) {
            this.child = child;
            this.call = call;
            this.observation = observation;
            promptId = call.id().toString();
            stateId = "state-" + promptId;
        }

        Result read() {
            try (var input = new BufferedInputStream(child.getInputStream())) {
                for (Optional<String> frame = PiJsonLines.readFrame(input);
                        frame.isPresent();
                        frame = PiJsonLines.readFrame(input)) {
                    Optional<Result> outcome = accept(rpc.decodeEvent(frame.orElseThrow()));
                    if (outcome.isPresent()) {
                        return outcome.orElseThrow();
                    }
                }
                throw new EOFException("PI_EXITED: runId=" + call.id());
            } catch (IOException | RuntimeException failure) {
                throw new CompletionException(failure);
            }
        }

        private Optional<Result> accept(Event event) throws IOException {
            if (event instanceof Resources resources) {
                observation.usage().recordResources(resources);
            } else if (event instanceof Ready announcement) {
                confirmReady(announcement);
            } else if (event instanceof Progress progress) {
                if (isAccepted) {
                    observation.usage().recordUsage(progress.usage());
                    if (progress.hasContent()) {
                        observation.usage().recordProgress(observation.budget());
                    }
                }
            } else if (event instanceof Response response) {
                return acceptResponse(response);
            } else if (event instanceof Answer answer) {
                recordAnswer(answer);
            } else if (event instanceof Settled settled) {
                return finish(settled);
            }
            return Optional.empty();
        }

        private void confirmReady(Ready announcement) {
            Integer expected = call.limits().outputTokens();
            if (expected == null) {
                return;
            }
            int actual = announcement.maxOutputTokens();
            if (actual != expected) {
                throw new IllegalStateException(
                        "PI_OUTPUT_LIMIT_UNSUPPORTED",
                        new IllegalArgumentException(
                                "runId="
                                        + call.id()
                                        + ", expected="
                                        + expected
                                        + ", actual="
                                        + actual));
            }
            // SIDE EFFECT: 只有限额一致才允许调用线程分发提示。
            ready.complete(null);
        }

        private Optional<Result> acceptResponse(Response response) throws IOException {
            if (promptId.equals(response.id())) {
                if (!response.isStarted()) {
                    throw new IOException("PROMPT_REJECTED: runId=" + call.id());
                }
                isAccepted = true;
            }
            if (stateId.equals(response.id())) {
                return Optional.of(new Result(finalText, response.sessionFile()));
            }
            return Optional.empty();
        }

        private void recordAnswer(Answer answer) throws IOException {
            // SIDE EFFECT: 模型失败时也要保留帧中已产生的用量，再报告失败。
            observation.usage().recordUsage(answer.usage());
            if (answer.isFailed()) {
                throw new IOException("MODEL_FAILED: runId=" + call.id());
            }
            finalText = answer.text();
            if (isAccepted && !finalText.isBlank()) {
                observation.usage().recordProgress(observation.budget());
            }
        }

        private Optional<Result> finish(Settled event) throws IOException {
            if (!isAccepted || event.isAborted() || finalText == null || finalText.isBlank()) {
                throw new IOException("NO_FINAL_ANSWER: runId=" + call.id());
            }
            if (!call.session().isPersistent()) {
                return Optional.of(new Result(finalText, null));
            }
            // SIDE EFFECT: 持久模式必须等原生会话文件的位置才能返回。
            rpc.send(child, Map.of(ID, stateId, TYPE, "get_state"));
            return Optional.empty();
        }
    }

    record Pending(Process child, CompletableFuture<Result> answer) {}

    record Generated(Result result, long promptNanos) {}

    record Observation(GenerationBudget budget, GenerationObserver observer, PiUsageCapture usage) {
        static Observation start(GenerationBudget budget, GenerationObserver observer) {
            return new Observation(budget, observer, new PiUsageCapture(observer));
        }

        void requireTimeRemaining() {
            String failure = budget.failure(System.nanoTime());
            if (failure != null) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
