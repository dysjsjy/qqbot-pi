/**
 * [INPUT]: 绑定提示上下文、输出额度与模型价格配置。
 * [OUTPUT]: 校验聊天、概括和画像共用的模型窗口预算。
 * [POS]: 预算组装及生成计量的部署配置，不决定群级功能开关。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("bot.memory")
public record MemoryProperties(
        @DefaultValue("8000") int inputTokens,
        @DefaultValue("800") int outputTokens,
        @DefaultValue("32768") int contextWindow,
        @DefaultValue("1024") int safetyTokens,
        @DefaultValue("1000") int summaryOutputTokens,
        @DefaultValue("USD") String currency,
        BigDecimal inputPrice,
        BigDecimal outputPrice,
        BigDecimal cacheReadPrice,
        BigDecimal cacheWritePrice,
        @DefaultValue("unset") String priceVersion,
        @DefaultValue("600") int profileOutputTokens) {
    @ConstructorBinding
    public MemoryProperties {
        if (inputTokens < 1024
                || inputTokens > 32768
                || outputTokens < 128
                || outputTokens > 4096
                || safetyTokens < 256
                || safetyTokens > 8192
                || summaryOutputTokens < 256
                || summaryOutputTokens > 4096
                || profileOutputTokens < 256
                || profileOutputTokens > 2048
                || (long) inputTokens
                                + Math.max(
                                        profileOutputTokens,
                                        Math.max(outputTokens, summaryOutputTokens))
                                + safetyTokens
                        > contextWindow) {
            throw new IllegalArgumentException("INVALID_MODEL_BUDGET");
        }
        for (BigDecimal price :
                new BigDecimal[] {inputPrice, outputPrice, cacheReadPrice, cacheWritePrice}) {
            if (price != null && price.signum() < 0) {
                throw new IllegalArgumentException("INVALID_MODEL_PRICE");
            }
        }
        if (currency == null
                || currency.isBlank()
                || priceVersion == null
                || priceVersion.isBlank()) {
            throw new IllegalArgumentException("INVALID_MODEL_PRICE");
        }
    }

    public MemoryProperties(
            int inputTokens,
            int outputTokens,
            int contextWindow,
            int safetyTokens,
            int summaryOutputTokens,
            String currency,
            BigDecimal inputPrice,
            BigDecimal outputPrice,
            BigDecimal cacheReadPrice,
            BigDecimal cacheWritePrice,
            String priceVersion) {
        this(
                inputTokens,
                outputTokens,
                contextWindow,
                safetyTokens,
                summaryOutputTokens,
                currency,
                inputPrice,
                outputPrice,
                cacheReadPrice,
                cacheWritePrice,
                priceVersion,
                600);
    }

    public static MemoryProperties defaults() {
        return new MemoryProperties(
                8000, 800, 32768, 1024, 1000, "USD", null, null, null, null, "unset");
    }
}
