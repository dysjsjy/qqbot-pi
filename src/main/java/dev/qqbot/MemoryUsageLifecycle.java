package dev.qqbot;

import dev.qqbot.usage.ModelCallStore;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@DependsOn("lifecycle")
public class MemoryUsageLifecycle implements InitializingBean {
    private final ModelCallStore calls;

    public MemoryUsageLifecycle(ModelCallStore calls) {
        this.calls = calls;
    }

    @Override
    public void afterPropertiesSet() {
        calls.recover();
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void cleanup() {
        calls.expire();
    }
}
