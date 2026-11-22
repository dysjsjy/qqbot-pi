package dev.qqbot.model;

import java.util.Objects;

public record ModelUsage(
        Long inputTokens,
        Long outputTokens,
        Long cacheReadTokens,
        Long cacheWriteTokens,
        Long reasoningTokens,
        UsageStatus status) {
    public ModelUsage {
        Objects.requireNonNull(status);
        for (Long count :
                new Long[] {
                    inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, reasoningTokens
                }) {
            if (count != null && count < 0) {
                throw new IllegalArgumentException("INVALID_MODEL_USAGE");
            }
        }
    }

    public static ModelUsage unknown() {
        return new ModelUsage(null, null, null, null, null, UsageStatus.UNKNOWN);
    }
}
