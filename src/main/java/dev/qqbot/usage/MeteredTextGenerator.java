/**
 * [INPUT]: 依赖 TextGenerator、单次调用观察状态、账目存储和链路记录。
 * [OUTPUT]: 记录分发及用量，再返回生成结果。把取消委托给实际生成器。
 * [POS]: 业务生成端口的计量装饰器。完成状态在刷新最后一次用量后提交。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.usage;

import dev.qqbot.model.*;
import dev.qqbot.model.GenerationObserver;
import dev.qqbot.model.ModelUsage;
import dev.qqbot.model.UsageStatus;
import dev.qqbot.trace.Trace;
import dev.qqbot.trace.TraceSink;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
public class MeteredTextGenerator implements TextGenerator {
    private static final Pattern SAFE_ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");
    private final TextGenerator delegate;
    private final ModelCallStore store;
    private TraceSink traces;
    private volatile long usageIntervalNanos = TimeUnit.SECONDS.toNanos(1);

    /** 配置可选的链路记录器。 */
    @Autowired(required = false)
    public void setTraces(TraceSink traces) {
        this.traces = traces;
    }

    /** 设置用量进度的最小写入间隔。零表示每次观察均写入。 */
    public void setUsageIntervalMillis(long millis) {
        usageIntervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, millis));
    }

    /** 用指定模型端口和账目存储创建计量装饰器。 */
    public MeteredTextGenerator(
            @Qualifier("piTextGenerator") TextGenerator delegate, ModelCallStore store) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    /** 登记并执行一次生成，向观察者公布分发状态和规范化用量。 */
    @Override
    public Result generate(GenerationRequest request, GenerationObserver observer) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(observer, "observer must not be null");
        boolean ownsTrace = startTrace(request);
        Failure outcome = null;
        try (var span = Trace.span("model.call")) {
            span.attr("purpose", request.purpose().name());
            return invoke(request, observer, span);
        } catch (RuntimeException | Error failure) {
            outcome = classifyFailure(failure);
            throw failure;
        } finally {
            if (ownsTrace) {
                endTrace(outcome);
            }
        }
    }

    private boolean startTrace(GenerationRequest request) {
        if (Trace.active()) {
            return false;
        }
        String name = "background." + request.purpose().name().toLowerCase(Locale.ROOT);
        Trace.begin(traces, Trace.BACKGROUND, name, request.runId(), request.groupId());
        return true;
    }

    private static void endTrace(Failure outcome) {
        if (outcome == null) {
            Trace.end(Trace.OK, null);
            return;
        }
        Trace.end(outcome.isCancelled() ? Trace.CANCELLED : Trace.ERROR, outcome.code());
    }

    private Result invoke(GenerationRequest request, GenerationObserver observer, Trace.Span span) {
        long estimated = TokenEstimator.estimateInput(request.systemPrompt(), request.userPrompt());
        var observation =
                new CallObservation(request.callId(), store, observer, () -> usageIntervalNanos);
        // SIDE EFFECT: 调用账目登记成功后才允许模型端口分发。
        store.begin(request);
        try {
            Result result = delegate.generate(request, observation);
            if (result.usage() != null) {
                observation.usage(result.usage());
            }
            observation.flush();
            store.finish(request.callId(), "SUCCEEDED", null);
            recordUsage(span, estimated, observation);
            return new Result(result.text(), result.checkpoint(), observation.latest());
        } catch (RuntimeException | Error failure) {
            var classified = classifyFailure(failure);
            recordSpanFailure(span, classified);
            finishFailedCall(request.callId(), observation, classified, failure);
            recordUsage(span, estimated, observation);
            throw failure;
        }
    }

    private static Failure classifyFailure(Throwable failure) {
        if (failure instanceof CancellationException) {
            return new Failure(true, "MODEL_CANCELLED");
        }
        String code = failure.getMessage();
        boolean isSafeCode = code != null && SAFE_ERROR_CODE.matcher(code).matches();
        return new Failure(false, isSafeCode ? code : "MODEL_CALL_FAILED");
    }

    private void finishFailedCall(
            UUID callId, CallObservation observation, Failure classified, Throwable failure) {
        try {
            observation.flush();
            store.finish(
                    callId, classified.isCancelled() ? "CANCELLED" : "FAILED", classified.code());
        } catch (RuntimeException accountingFailure) {
            failure.addSuppressed(accountingFailure);
        }
    }

    private static void recordUsage(Trace.Span span, long estimated, CallObservation observation) {
        ModelUsage used = observation.latest();
        span.tokens(
                estimated,
                used.inputTokens(),
                used.outputTokens(),
                used.cacheReadTokens(),
                used.cacheWriteTokens(),
                used.reasoningTokens());
        span.attr("usageStatus", used.status().name());
        span.attr("usageEvents", observation.events());
        span.attr("usageWrites", observation.writes());
    }

    private static void recordSpanFailure(Trace.Span span, Failure failure) {
        if (failure.isCancelled()) {
            span.cancelled(failure.code());
        } else {
            span.fail(failure.code());
        }
    }

    /** 取消指定运行。 */
    @Override
    public void cancel(UUID runId) {
        delegate.cancel(runId);
    }

    private record Failure(boolean isCancelled, String code) {}

    static final class CallObservation implements GenerationObserver {
        private final UUID callId;
        private final ModelCallStore store;
        private final GenerationObserver target;
        private final LongSupplier intervalNanos;
        private final AtomicInteger events = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();
        private final AtomicLong persistedAt = new AtomicLong();
        private final AtomicReference<ModelUsage> latest =
                new AtomicReference<>(ModelUsage.unknown());
        private final AtomicBoolean pending = new AtomicBoolean();

        CallObservation(
                UUID callId,
                ModelCallStore store,
                GenerationObserver target,
                LongSupplier intervalNanos) {
            this.callId = callId;
            this.store = store;
            this.target = target;
            this.intervalNanos = intervalNanos;
        }

        @Override
        public void dispatched() {
            // SIDE EFFECT: 先持久化分发，后通知上游增加分发计数。
            store.dispatched(callId);
            target.dispatched();
        }

        @Override
        public void usage(ModelUsage reported) {
            reconcileUsage(latest.get(), reported).ifPresent(this::observe);
        }

        private void observe(ModelUsage value) {
            events.incrementAndGet();
            long now = System.nanoTime();
            long lastWrite = persistedAt.get();
            if (lastWrite == 0 || now - lastWrite >= intervalNanos.getAsLong()) {
                store.usage(callId, value);
                persistedAt.set(now == 0 ? 1 : now);
                pending.set(false);
                writes.incrementAndGet();
            } else {
                pending.set(true);
            }
            latest.set(value);
            target.usage(value);
        }

        void flush() {
            if (pending.getAndSet(false)) {
                store.usage(callId, latest.get());
                writes.incrementAndGet();
            }
        }

        ModelUsage latest() {
            return latest.get();
        }

        int events() {
            return events.get();
        }

        int writes() {
            return writes.get();
        }

        static Optional<ModelUsage> reconcileUsage(ModelUsage previous, ModelUsage next) {
            if (isCompleteReport(next)) {
                return Optional.of(next);
            }
            if (previous.status() == UsageStatus.REPORTED) {
                return Optional.empty();
            }
            return Optional.of(
                    new ModelUsage(
                            prefer(next.inputTokens(), previous.inputTokens()),
                            prefer(next.outputTokens(), previous.outputTokens()),
                            prefer(next.cacheReadTokens(), previous.cacheReadTokens()),
                            prefer(next.cacheWriteTokens(), previous.cacheWriteTokens()),
                            prefer(next.reasoningTokens(), previous.reasoningTokens()),
                            UsageStatus.UNKNOWN));
        }

        private static boolean isCompleteReport(ModelUsage usage) {
            return usage.status() == UsageStatus.REPORTED
                    && usage.inputTokens() != null
                    && usage.outputTokens() != null
                    && usage.cacheReadTokens() != null
                    && usage.cacheWriteTokens() != null;
        }

        private static Long prefer(Long reported, Long previous) {
            return reported == null ? previous : reported;
        }
    }
}
