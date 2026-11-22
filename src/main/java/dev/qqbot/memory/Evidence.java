package dev.qqbot.memory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Evidence(
        String sourceKey,
        SourceKind kind,
        String body,
        List<UUID> messageIds,
        Instant occurredAt,
        double score) {
    public Evidence {
        Objects.requireNonNull(sourceKey);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(body);
        messageIds = List.copyOf(messageIds);
        Objects.requireNonNull(occurredAt);
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("INVALID_EVIDENCE_SCORE");
        }
    }
}
