/**
 * [INPUT]: 依赖摘要归档队列与计量生成端口。
 * [OUTPUT]: 串行运行已认领的摘要，回排时结束本轮；关闭时取消活跃调用。
 * [POS]: 定时轮询边界；选源、生成和发布由归档服务负责。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.model.TextGenerator;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MarkdownMemoryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(MarkdownMemoryWorker.class);
    private static final int MAX_JOBS_PER_POLL = 20;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final String JOB_ID = "id";
    private final MarkdownArchive archive;
    private final TextGenerator pi;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean busy;
    private volatile UUID active;

    public MarkdownMemoryWorker(MarkdownArchive archive, TextGenerator pi) {
        this.archive = archive;
        this.pi = pi;
    }

    @Scheduled(fixedDelay = 5000)
    public synchronized void tick() {
        if (busy) {
            return;
        }
        // SIDE EFFECT: 同实例只允许一个摘要轮询占用后台执行线程。
        busy = true;
        executor.submit(this::runPoll);
    }

    private void runPoll() {
        try {
            // SIDE EFFECT: 批次准备和认领分别提交；生成期间不持认领事务。
            archive.prepare();
            for (int count = 0; count < MAX_JOBS_PER_POLL; count++) {
                var job = archive.claim();
                if (job == null) {
                    return;
                }
                UUID id = (UUID) job.get(JOB_ID);
                active = id;
                archive.process(job, pi);
                active = null;
                if (archive.deferred(id)) {
                    return;
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn("MARKDOWN_WORKER_FAILED exception={}", failure.getClass().getSimpleName());
        } finally {
            active = null;
            busy = false;
        }
    }

    @PreDestroy
    public void close() {
        if (active != null) {
            pi.cancel(active);
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }
}
