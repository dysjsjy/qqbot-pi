package dev.qqbot.memory;

import java.util.Objects;
import java.util.UUID;

public record ContextRequest(
        MemoryScope scope,
        UUID conversationId,
        long generation,
        String senderId,
        String question,
        QueryMode mode) {
    public ContextRequest {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(conversationId);
        Objects.requireNonNull(senderId);
        Objects.requireNonNull(question);
        Objects.requireNonNull(mode);
    }
}
