package dev.qqbot.memory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MemoryWorker {
    private final VectorMemory vectors;
    private final MemoryMaintenance maintenance;

    public MemoryWorker(VectorMemory vectors, MemoryMaintenance maintenance) {
        this.vectors = vectors;
        this.maintenance = maintenance;
    }

    @Scheduled(fixedDelay = 5000)
    public void index() {
        vectors.indexPending(20);
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void cleanup() {
        maintenance.cleanup();
    }
}
