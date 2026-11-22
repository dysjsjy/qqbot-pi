package dev.qqbot.model;

import java.util.Objects;
import java.util.UUID;

public record GenerationRequest(
        UUID runId,
        UUID callId,
        UUID groupId,
        long epoch,
        Purpose purpose,
        String systemPrompt,
        String userPrompt,
        int maxInputTokens,
        int maxOutputTokens,
        int timeoutSeconds) {
    public GenerationRequest {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(callId);
        Objects.requireNonNull(groupId);
        Objects.requireNonNull(purpose);
        Objects.requireNonNull(systemPrompt);
        Objects.requireNonNull(userPrompt);
        if (maxInputTokens < 1 || maxOutputTokens < 1 || timeoutSeconds < 1) {
            throw new IllegalArgumentException("INVALID_MODEL_BUDGET");
        }
    }
}
