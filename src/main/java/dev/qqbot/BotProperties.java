/**
 * [INPUT]: 依赖 Spring bot 配置命名空间。
 * [OUTPUT]: 提供管理员、pi、embedding 与网关类型化配置。
 * [POS]: 配置边界；各模块按职责读取对应配置。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("bot")
public record BotProperties(
        String adminUsername,
        String adminPasswordHash,
        Path stateDir,
        boolean scheduling,
        Pi pi,
        Embedding embedding,
        Qq qq,
        Gateway weixin,
        @DefaultValue Rag rag) {
    public record Rag(@DefaultValue("false") boolean enabled) {}

    public boolean ragEnabled() {
        return rag != null && rag.enabled();
    }

    public record Pi(
            String node,
            String cli,
            String provider,
            String model,
            String keyEnv,
            String apiKey,
            int timeoutSeconds,
            String baseUrl,
            @DefaultValue("300") int maxTimeoutSeconds,
            @DefaultValue("120") int queueTimeoutSeconds,
            @DefaultValue("420") int requestTimeoutSeconds) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Pi {
            if (timeoutSeconds < 1
                    || maxTimeoutSeconds < 1
                    || queueTimeoutSeconds < 1
                    || requestTimeoutSeconds < 1) {
                throw new IllegalArgumentException("Pi timeout windows must be positive");
            }
        }

        public Pi(
                String node,
                String cli,
                String provider,
                String model,
                String keyEnv,
                String apiKey,
                int timeoutSeconds,
                String baseUrl) {
            this(
                    node,
                    cli,
                    provider,
                    model,
                    keyEnv,
                    apiKey,
                    timeoutSeconds,
                    baseUrl,
                    300,
                    120,
                    420);
        }
    }

    public record Embedding(
            String url, String apiKey, String model, int dimensions, double minSimilarity) {}

    public record OfficialQq(
            String appId,
            String appSecret,
            @DefaultValue("https://api.bot.qq.com") String apiBaseUrl,
            @DefaultValue("https://api.bot.qq.com/app/getAppAccessToken") String tokenUrl,
            boolean allowActiveMessages,
            @DefaultValue("websocket") String transport,
            @DefaultValue("") String botOpenid) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public OfficialQq {}

        public OfficialQq(
                String appId,
                String appSecret,
                String apiBaseUrl,
                String tokenUrl,
                boolean allowActiveMessages) {
            this(appId, appSecret, apiBaseUrl, tokenUrl, allowActiveMessages, "websocket", "");
        }

        public OfficialQq(
                String appId,
                String appSecret,
                String apiBaseUrl,
                String tokenUrl,
                boolean allowActiveMessages,
                String transport) {
            this(appId, appSecret, apiBaseUrl, tokenUrl, allowActiveMessages, transport, "");
        }
    }

    public record Qq(boolean enabled, @DefaultValue OfficialQq official) {
        public String accountId() {
            return official.appId();
        }
    }

    public record Gateway(
            boolean enabled,
            String baseUrl,
            String token,
            String accountId,
            String webhookSecret) {}

    public Gateway gateway(String channel) {
        return switch (channel) {
            case "QQ" -> new Gateway(qq.enabled(), "", "", qq.accountId(), "");
            case "WEIXIN" -> weixin;
            default -> throw new IllegalArgumentException("Unsupported channel");
        };
    }
}
