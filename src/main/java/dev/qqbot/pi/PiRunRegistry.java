/**
 * [INPUT]: 接收调用 ID、线程和执行槽限额。
 * [OUTPUT]: 登记运行，分配公平执行槽，并公布运行取消和子进程状态。
 * [POS]: 单个运行时的并发边界；不创建工作目录或终止进程。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.pi;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

final class PiRunRegistry {
    private static final String BACKGROUND_BUSY = "BACKGROUND_BUSY";
    private static final int TOTAL_SLOTS = 2;
    private static final int BACKGROUND_SLOTS = 1;
    private final Semaphore slots = new Semaphore(TOTAL_SLOTS, true);
    private final Semaphore background = new Semaphore(BACKGROUND_SLOTS, true);
    private final ConcurrentMap<UUID, Run> active = new ConcurrentHashMap<>();

    Run register(UUID id) {
        var run = new Run(id, Thread.currentThread());
        // SIDE EFFECT: 先公开登记，以便等待执行槽时也能取消。
        if (active.putIfAbsent(id, run) != null) {
            throw new IllegalStateException("PI_RUN_ACTIVE");
        }
        return run;
    }

    void acquire(Run run, boolean isBackground, int waitSeconds) throws InterruptedException {
        run.requireActive();
        if (isBackground) {
            run.hasBackgroundSlot = background.tryAcquire(waitSeconds, TimeUnit.SECONDS);
            if (!run.hasBackgroundSlot) {
                throw new IllegalStateException(BACKGROUND_BUSY);
            }
        }
        run.requireActive();
        run.hasSlot = slots.tryAcquire(waitSeconds, TimeUnit.SECONDS);
        if (!run.hasSlot) {
            throw new IllegalStateException(isBackground ? BACKGROUND_BUSY : "PI_BUSY");
        }
        run.requireActive();
    }

    Optional<Run> find(UUID id) {
        return Optional.ofNullable(active.get(id));
    }

    Set<UUID> activeIds() {
        return Set.copyOf(active.keySet());
    }

    void unregister(Run run) {
        active.remove(run.id, run);
    }

    void release(Run run) {
        if (run.hasSlot) {
            slots.release();
            run.hasSlot = false;
        }
        if (run.hasBackgroundSlot) {
            background.release();
            run.hasBackgroundSlot = false;
        }
    }

    static final class Run {
        static final String CANCELLED = "PI_CANCELLED";
        private final UUID id;
        private final Thread owner;
        private volatile boolean isCancelled;
        private volatile Process child;
        private Path workspace;
        private boolean hasSlot;
        private boolean hasBackgroundSlot;

        Run(UUID id, Thread owner) {
            this.id = id;
            this.owner = owner;
        }

        void requestCancellation() {
            isCancelled = true;
            owner.interrupt();
        }

        void requireActive() {
            if (isCancelled) {
                throw new CancellationException(CANCELLED);
            }
        }

        Optional<Process> findChild() {
            return Optional.ofNullable(child);
        }

        void trackChild(Process child) {
            this.child = child;
        }

        Optional<Path> findWorkspace() {
            return Optional.ofNullable(workspace);
        }

        void trackWorkspace(Path workspace) {
            this.workspace = workspace;
        }
    }
}
