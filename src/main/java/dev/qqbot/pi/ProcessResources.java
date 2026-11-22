/**
 * [INPUT]: 接收一次生成的子进程或创建的工作目录。
 * [OUTPUT]: 终止进程树，清理运行目录并恢复线程中断标记。
 * [POS]: Pi 运行时的资源回收边界；不处理 RPC。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.pi;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ProcessResources {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessResources.class);
    private static final long SOFT_STOP_MILLIS = 200;
    private static final long STOP_POLL_MILLIS = 20;
    private static final long PROCESS_STOP_SECONDS = 3;
    private static final long WORKSPACE_CLEANUP_SECONDS = 2;
    private static final long CLEANUP_RETRY_MILLIS = 25;
    private static final String PROCESS_STOP_TIMEOUT = "PI_PROCESS_STOP_TIMEOUT";

    private ProcessResources() {}

    static void stop(Process child) {
        try (var interrupts = new PreservedInterrupt()) {
            var descendants = child.descendants().toList();
            // SIDE EFFECT: 先终止整棵进程树，再关闭管道，避免残留进程锁住工作目录。
            terminateTree(child, descendants, interrupts);
            awaitTreeExit(child, descendants, interrupts);
            closePipes(child);
        }
    }

    private static void terminateTree(
            Process child, List<ProcessHandle> descendants, PreservedInterrupt interrupts) {
        descendants.forEach(ProcessHandle::destroyForcibly);
        child.destroy();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SOFT_STOP_MILLIS);
        while (child.isAlive() && System.nanoTime() < deadline) {
            interrupts.waitFor(child);
        }
        if (child.isAlive()) {
            child.destroyForcibly();
        }
    }

    private static void awaitTreeExit(
            Process child, List<ProcessHandle> descendants, PreservedInterrupt interrupts) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_STOP_SECONDS);
        for (ProcessHandle descendant : descendants) {
            interrupts.awaitExit(descendant, deadline);
        }
        interrupts.awaitExit(child.toHandle(), deadline);
    }

    private static void closePipes(Process child) {
        for (var pipe :
                List.of(child.getInputStream(), child.getErrorStream(), child.getOutputStream())) {
            try {
                pipe.close();
            } catch (IOException closed) {
                LOG.debug("Pi terminated with an unavailable pipe: pid={}", child.pid());
            }
        }
    }

    static void deleteWorkspace(Path workspace) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WORKSPACE_CLEANUP_SECONDS);
        try (var interrupts = new PreservedInterrupt()) {
            while (!tryDeleteWorkspace(workspace, deadline)) {
                interrupts.pauseBeforeRetry();
            }
        }
    }

    private static boolean tryDeleteWorkspace(Path workspace, long deadline) {
        try {
            if (Files.exists(workspace)) {
                deleteTree(workspace);
            }
            return true;
        } catch (IOException failure) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("PI_WORKSPACE_CLEANUP_FAILED", failure);
            }
            return false;
        }
    }

    private static void deleteTree(Path workspace) throws IOException {
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class PreservedInterrupt implements AutoCloseable {
        private boolean isInterrupted = Thread.interrupted();

        void waitFor(Process child) {
            try {
                child.waitFor(STOP_POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                isInterrupted = true;
            }
        }

        void awaitExit(ProcessHandle child, long deadline) {
            while (child.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IllegalStateException(PROCESS_STOP_TIMEOUT);
                }
                try {
                    child.onExit().get(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException interrupted) {
                    isInterrupted = true;
                } catch (ExecutionException | TimeoutException failure) {
                    throw new IllegalStateException(PROCESS_STOP_TIMEOUT, failure);
                }
            }
        }

        void pauseBeforeRetry() {
            try {
                Thread.sleep(CLEANUP_RETRY_MILLIS);
            } catch (InterruptedException interrupted) {
                isInterrupted = true;
            }
        }

        @Override
        public void close() {
            if (isInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
