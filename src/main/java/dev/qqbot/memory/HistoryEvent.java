package dev.qqbot.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record HistoryEvent(
        String sourceKey,
        long seq,
        Instant occurredAt,
        Role role,
        String senderId,
        String senderName,
        String body,
        UUID conversationId,
        Long generation,
        String replyToKey,
        boolean explicitlyReferenced) {
    public HistoryEvent {
        Objects.requireNonNull(sourceKey);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(role);
        Objects.requireNonNull(senderId);
        Objects.requireNonNull(senderName);
        Objects.requireNonNull(body);
    }
}
