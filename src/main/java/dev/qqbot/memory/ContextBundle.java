package dev.qqbot.memory;

import java.util.List;
import java.util.Objects;

public record ContextBundle(
        String systemPrompt,
        String userPrompt,
        List<Evidence> evidence,
        ContextStats stats,
        CitationMode citationMode) {
    public ContextBundle {
        Objects.requireNonNull(systemPrompt);
        Objects.requireNonNull(userPrompt);
        evidence = List.copyOf(evidence);
        Objects.requireNonNull(stats);
        Objects.requireNonNull(citationMode);
    }
}
