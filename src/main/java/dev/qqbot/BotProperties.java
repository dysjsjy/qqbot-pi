package dev.qqbot;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;
@ConfigurationProperties("bot")
public record BotProperties(String adminUsername,String adminPasswordHash,Path stateDir,boolean scheduling,
 Pi pi,Embedding embedding,Gateway qq,Gateway weixin) {
 public record Pi(String node,String cli,String provider,String model,String keyEnv,String apiKey,int timeoutSeconds,String baseUrl) {}
 public record Embedding(String url,String apiKey,String model,int dimensions,double minSimilarity) {}
 public record Gateway(boolean enabled,String baseUrl,String token,String accountId,String webhookSecret) {}
 public Gateway gateway(String channel) { return switch(channel) {case "QQ"->qq;case "WEIXIN"->weixin;default->throw new IllegalArgumentException("Unsupported channel");}; }
}
