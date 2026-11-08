package dev.qqbot.memory;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@Component
public class MemoryWorker {
    private final MemoryService memory;
    public MemoryWorker(MemoryService memory){this.memory=memory;}
    @Scheduled(fixedDelay=5000) public void index(){memory.indexPending(20);}
    @Scheduled(fixedDelay=3600000,initialDelay=60000) public void cleanup(){memory.cleanup();}
}
