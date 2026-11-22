/**
 * [INPUT]: 接收已经解析的模型用量、子进程资源帧及生成进度时钟。
 * [OUTPUT]: 暴露用量快照和计时，并将帐目信息通知生成观察者。
 * [POS]: 单次模型调用的共享观测状态；读取线程写入，运行线程在完成时读取。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.pi;

import dev.qqbot.model.GenerationObserver;
import dev.qqbot.model.ModelUsage;
import dev.qqbot.model.UsageStatus;
import dev.qqbot.pi.PiJsonLines.Resources;
import dev.qqbot.trace.Trace;
import java.util.concurrent.TimeUnit;

final class PiUsageCapture {
    private final GenerationObserver observer;
    volatile ModelUsage value = ModelUsage.unknown();
    private volatile boolean observed;
    volatile long childCpuUs = -1;
    volatile long childRssBytes = -1;
    volatile long childHeapBytes = -1;
    volatile long firstTokenNanos;
    volatile int frames;

    PiUsageCapture(GenerationObserver observer) {
        this.observer = observer;
    }

    void recordResources(Resources event) {
        childCpuUs = event.cpuMicros();
        childRssBytes = Math.max(childRssBytes, event.rssBytes());
        childHeapBytes = event.heapBytes();
        frames++;
    }

    void recordProgress(GenerationBudget budget) {
        if (firstTokenNanos == 0) {
            firstTokenNanos = System.nanoTime();
        }
        budget.progress(System.nanoTime());
    }

    void recordUsage(ModelUsage next) {
        if (next == null
                || (value.status() == UsageStatus.REPORTED
                        && next.status() != UsageStatus.REPORTED)) {
            return;
        }
        // SIDE EFFECT: 通知观察者保存当前用量；不以不完整帧覆盖可信的计量。
        value = next;
        observed = true;
        observer.usage(next);
    }

    void finish() {
        if (observed) {
            observer.usage(value);
        }
    }

    void describe(Trace.Span phase, long promptNanos) {
        if (firstTokenNanos != 0) {
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(firstTokenNanos - promptNanos);
            phase.attr("firstTokenMs", Math.max(0, latencyMs));
        }
        if (frames > 0) {
            phase.attr("childCpuUs", childCpuUs);
            phase.attr("childRssBytes", childRssBytes);
            phase.attr("childHeapBytes", childHeapBytes);
            phase.attr("usageFrames", frames);
        }
    }
}
