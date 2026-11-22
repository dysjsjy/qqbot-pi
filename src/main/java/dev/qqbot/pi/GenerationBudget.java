/**
 * [INPUT]: 接收单调时间、限制秒数及输入码点数。
 * [OUTPUT]: 判断首内容、空闲和绝对截止时间。
 * [POS]: 生成线程与读取线程共享的时钟状态；不读写 I/O。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.pi;

import java.time.Duration;

public final class GenerationBudget {
    private final long started;
    private final long window;
    private final long maximum;
    private long allowed;
    private long lastProgress;
    private boolean progressed;
    private String expired;

    public GenerationBudget(
            long started, int windowSeconds, int maximumSeconds, int inputCodepoints) {
        if (windowSeconds < 1 || maximumSeconds < 1) {
            throw new IllegalArgumentException("Invalid generation budget");
        }
        this.started = started;
        window = Duration.ofSeconds(windowSeconds).toNanos();
        maximum = Duration.ofSeconds(maximumSeconds).toNanos();
        int tier = inputCodepoints > 12000 ? 3 : inputCodepoints > 4000 ? 2 : 1;
        allowed = Math.min(maximum, window * tier);
    }

    public synchronized void progress(long now) {
        if (failure(now) != null) {
            return;
        }
        lastProgress = now;
        progressed = true;
    }

    public synchronized String failure(long now) {
        if (expired != null) {
            return expired;
        }
        long elapsed = now - started;
        if (elapsed >= maximum) {
            return expired = "PI_DEADLINE";
        }
        if (!progressed && elapsed >= allowed) {
            return expired = "PI_FIRST_CONTENT_TIMEOUT";
        }
        if (progressed && now - lastProgress >= window) {
            return expired = "PI_IDLE_TIMEOUT";
        }
        if (progressed && elapsed >= allowed) {
            allowed = Math.min(maximum, elapsed + window);
        }
        return null;
    }
}
