package dev.qqbot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("bot.memory.raw-archive")
public record RawArchiveProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("67108864") long segmentBytes,
        @DefaultValue("86400") long segmentMaxAgeSeconds,
        @DefaultValue("1000") int pollMs,
        @DefaultValue("500") int batchMessages,
        @DefaultValue("1048576") int batchBytes,
        @DefaultValue("4096") int indexIntervalBytes,
        @DefaultValue("16") int maxOpenSegments,
        @DefaultValue("60000") int gcPollMs) {
    public RawArchiveProperties {
        if (segmentBytes < 8L * 1024 * 1024
                || segmentBytes > 1024L * 1024 * 1024
                || segmentMaxAgeSeconds < 60
                || segmentMaxAgeSeconds > 604800
                || pollMs < 100
                || pollMs > 60000
                || batchMessages < 1
                || batchMessages > 2000
                || batchBytes < 65536
                || batchBytes > 8 * 1024 * 1024
                || indexIntervalBytes < 1024
                || indexIntervalBytes > 65536
                || maxOpenSegments < 1
                || maxOpenSegments > 64
                || gcPollMs < 1000
                || gcPollMs > 3600000) {
            throw new IllegalArgumentException("Invalid RAW archive configuration");
        }
    }

    public static RawArchiveProperties defaults() {
        return new RawArchiveProperties(true, 67108864, 86400, 1000, 500, 1048576, 4096, 16, 60000);
    }
}
