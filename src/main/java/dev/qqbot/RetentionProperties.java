/**
 * [INPUT]: 绑定部署设置的热窗、冷留存和过程保留期。
 * [OUTPUT]: 校验窗口关系并提供各自的时间水位。
 * [POS]: 在线读取与物理清理共用的留存口径。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("bot.memory.retention")
public record RetentionProperties(
        @DefaultValue("30") int hotWindowDays,
        @DefaultValue("0") int archiveRetentionDays,
        @DefaultValue("30") int processRetentionDays) {
    public RetentionProperties {
        if (hotWindowDays < 7
                || hotWindowDays > 365
                || processRetentionDays < 7
                || processRetentionDays > 365
                || archiveRetentionDays < 0
                || archiveRetentionDays > 3650
                || (archiveRetentionDays != 0 && archiveRetentionDays < hotWindowDays)) {
            throw new IllegalArgumentException("INVALID_RETENTION_POLICY");
        }
    }

    public static RetentionProperties defaults() {
        return new RetentionProperties(30, 0, 30);
    }

    public boolean finiteArchive() {
        return archiveRetentionDays > 0;
    }

    public Instant hotCutoff(Instant now) {
        return now.minus(Duration.ofDays(hotWindowDays));
    }

    public Instant archiveCutoff(Instant now) {
        return finiteArchive() ? now.minus(Duration.ofDays(archiveRetentionDays)) : Instant.EPOCH;
    }

    public Instant archiveExpiry(Instant start) {
        return finiteArchive()
                ? start.plus(Duration.ofDays(archiveRetentionDays))
                : Instant.ofEpochMilli(Long.MAX_VALUE);
    }

    public Instant processCutoff(Instant now) {
        return now.minus(Duration.ofDays(processRetentionDays));
    }
}
