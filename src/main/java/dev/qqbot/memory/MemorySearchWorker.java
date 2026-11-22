package dev.qqbot.memory;

import dev.qqbot.persistence.Db;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MemorySearchWorker {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MemorySearchWorker.class);
    private final Db db;
    private final MemorySearchIndex index;

    public MemorySearchWorker(Db db, MemorySearchIndex index) {
        this.db = db;
        this.index = index;
    }

    @Scheduled(fixedDelay = 5000)
    public void tick() {
        for (var group : db.list("select id from group_config where enabled order by id")) {
            UUID groupId = (UUID) group.get("id");
            try {
                index.indexPending(groupId, 100);
            } catch (RuntimeException failure) {
                log.warn(
                        "MEMORY_SEARCH_INDEX_FAILED group={} exception={}",
                        groupId,
                        failure.getClass().getSimpleName());
            }
        }
    }
}
