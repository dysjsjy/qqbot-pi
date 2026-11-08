package dev.qqbot;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;
import java.time.Clock;
@SpringBootApplication
@EnableConfigurationProperties(BotProperties.class)
public class BotApplication {
 public static void main(String[] args) { SpringApplication.run(BotApplication.class,args); }
 @Bean Clock clock() { return Clock.systemUTC(); }
 @org.springframework.context.annotation.Configuration
 @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="bot.scheduling",havingValue="true",matchIfMissing=true)
 @EnableScheduling
 static class Scheduling {}
}
