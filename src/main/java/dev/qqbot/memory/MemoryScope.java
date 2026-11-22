package dev.qqbot.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MemoryScope(
        UUID groupId,
        long epoch,
        UUID triggerMessageId,
        long beforeSeq,
        Instant occurredBeforeOrAt,
        Instant evaluatedAt) {
    public MemoryScope {
        Objects.requireNonNull(groupId);
        Objects.requireNonNull(occurredBeforeOrAt);
        Objects.requireNonNull(evaluatedAt);
        if (beforeSeq < 1) {
            throw new IllegalArgumentException("INVALID_CONTEXT_BOUNDARY");
        }
    }
}
