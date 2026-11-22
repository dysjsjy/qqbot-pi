package dev.qqbot.memory;

import dev.qqbot.RawArchiveProperties;
import dev.qqbot.persistence.Db;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@DependsOn("rawArchiveLifecycle")
public class RawArchiveWorker {
    private final Db db;
    private final RawArchiveService archive;
    private final RawArchiveProperties properties;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean busy;

    public RawArchiveWorker(Db db, RawArchiveService archive, RawArchiveProperties properties) {
        this.db = db;
        this.archive = archive;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${bot.memory.raw-archive.poll-ms:1000}")
    public synchronized void tick() {
        if (busy || !properties.enabled()) {
            return;
        }
        busy = true;
        executor.submit(
                () -> {
                    try {
                        runOnce();
                    } finally {
                        synchronized (this) {
                            busy = false;
                        }
                    }
                });
    }

    public void runOnce() {
        for (var row : db.list("select id from group_config where enabled order by id")) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            try {
                archive.archiveBatch((UUID) row.get("id"));
            } catch (Exception failure) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn(
                                "RAW_ARCHIVE_FAILED exception={}",
                                failure.getClass().getSimpleName());
            }
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException failure) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
