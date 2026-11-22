/**
 * [INPUT]: 依赖 Spring Boot、类型化配置与系统时钟。
 * [OUTPUT]: 启动服务，提供 UTC 时钟与可配置后台调度。
 * [POS]: 服务组合根；业务模块通过注入依赖协作。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({
    BotProperties.class,
    MemoryProperties.class,
    RawArchiveProperties.class,
    RetentionProperties.class,
    MemberProfileProperties.class
})
public class BotApplication {
    public static void main(String[] args) {
        SpringApplication.run(BotApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "bot.scheduling",
            havingValue = "true",
            matchIfMissing = true)
    @EnableScheduling
    static class Scheduling {}
}
