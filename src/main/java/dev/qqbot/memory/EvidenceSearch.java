package dev.qqbot.memory;

import dev.qqbot.BotProperties;
import dev.qqbot.trace.Trace;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class EvidenceSearch {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(EvidenceSearch.class);
    private final MemorySearchIndex index;
    private final VectorMemory vectors;
    private final BotProperties properties;

    public EvidenceSearch(MemorySearchIndex index, VectorMemory vectors, BotProperties properties) {
        this.index = index;
        this.vectors = vectors;
        this.properties = properties;
    }

    public List<Evidence> search(
            MemoryScope scope, String question, QueryMode mode, int requested) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(mode);
        if (requested < 1
                || SearchText.queryTerms(question == null ? "" : question).isEmpty()
                || !index.active(scope)) {
            return List.of();
        }
        int limit = Math.min(4, requested);
        boolean vectorAllowed = mode == QueryMode.HISTORY && properties.ragEnabled();
        MemorySearchIndex.Matches lexical;
        try (var span = Trace.span("search.lexical")) {
            lexical = index.lexical(scope, question, mode, vectorAllowed ? 190 : 200);
        }
        List<Evidence> semantic = List.of();
        if (vectorAllowed && lexical.evidence().size() < 4) {
            try {
                try (var span = Trace.span("search.vector")) {
                    semantic = vectors.search(scope, question, 10).evidence();
                }
            } catch (RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) {
                    throw failure;
                }
                log.warn(
                        "VECTOR_SEARCH_UNAVAILABLE group={} exception={}",
                        scope.groupId(),
                        failure.getClass().getSimpleName());
            }
        }
        return fuse(lexical.evidence(), semantic, limit);
    }

    static List<Evidence> fuse(List<Evidence> lexical, List<Evidence> semantic, int limit) {
        var values = new LinkedHashMap<String, Evidence>();
        var scores = new HashMap<String, Double>();
        for (var route : List.of(lexical, semantic)) {
            int rank = 0;
            var routeMessages = new HashSet<UUID>();
            var routeKeys = new HashSet<String>();
            for (var item : route) {
                if (routeKeys.contains(item.sourceKey())
                        || item.messageIds().stream().anyMatch(routeMessages::contains)) {
                    continue;
                }
                if (rank >= 10) {
                    break;
                }
                routeKeys.add(item.sourceKey());
                routeMessages.addAll(item.messageIds());
                rank++;
                values.putIfAbsent(item.sourceKey(), item);
                scores.merge(item.sourceKey(), 1.0 / (60 + rank), Double::sum);
            }
        }
        var ordered =
                values.values().stream()
                        .sorted(
                                Comparator.<Evidence>comparingDouble(
                                                item -> scores.get(item.sourceKey()))
                                        .reversed()
                                        .thenComparing(Evidence::sourceKey))
                        .toList();
        var result = new ArrayList<Evidence>();
        var messages = new HashSet<UUID>();
        for (var item : ordered) {
            if (item.messageIds().stream().anyMatch(messages::contains)) {
                continue;
            }
            result.add(
                    new Evidence(
                            item.sourceKey(),
                            item.kind(),
                            item.body(),
                            item.messageIds(),
                            item.occurredAt(),
                            scores.get(item.sourceKey())));
            messages.addAll(item.messageIds());
            if (result.size() >= limit) {
                break;
            }
        }
        return List.copyOf(result);
    }
}
