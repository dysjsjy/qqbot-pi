package dev.qqbot.model;

import java.nio.charset.StandardCharsets;

public final class TokenEstimator {
    private TokenEstimator() {}

    public static int estimateInput(String systemPrompt, String userPrompt) {
        return Math.addExact(
                256, Math.addExact(estimateText(systemPrompt), estimateText(userPrompt)));
    }

    public static int estimateText(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
