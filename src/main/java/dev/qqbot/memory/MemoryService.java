/**
 * [INPUT]: 依赖分层上下文、Markdown 搜索与可选向量搜索。
 * [OUTPUT]: 为回复提供按需检索和上下文。为管理台提供直接搜索与统计。
 * [POS]: 回复检索受门控；管理台直接搜索不受回复门控限制。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.BotProperties;
import dev.qqbot.trace.Trace;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MemoryService {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MemoryService.class);
    private final MarkdownSearch markdown;
    private final MemoryContext context;
    private final VectorMemory vectors;
    private final BotProperties properties;
    private final EvidenceSearch evidence;

    public MemoryService(
            MarkdownSearch markdown,
            MemoryContext context,
            VectorMemory vectors,
            BotProperties properties,
            EvidenceSearch evidence) {
        this.markdown = markdown;
        this.context = context;
        this.vectors = vectors;
        this.properties = properties;
        this.evidence = evidence;
    }

    public ContextBundle assemble(ContextRequest request) {
        try (var span = Trace.span("memory.assemble")) {
            ContextBundle bundle;
            try (var build = Trace.span("memory.context")) {
                bundle =
                        context.assemble(
                                request,
                                () -> {
                                    try (var search = Trace.span("memory.search")) {
                                        return evidence.search(
                                                request.scope(),
                                                request.question(),
                                                request.mode(),
                                                4);
                                    }
                                });
            }
            span.attr("evidence", bundle.evidence().size());
            span.attr("systemBytes", bundle.systemPrompt().getBytes(StandardCharsets.UTF_8).length);
            span.attr("promptBytes", bundle.userPrompt().getBytes(StandardCharsets.UTF_8).length);
            if (bundle.stats() != null) {
                span.attr(
                        "layerTokens",
                        new LinkedHashMap<String, Object>(bundle.stats().layerTokens()));
            }
            return bundle;
        }
    }

    public int inputLimit() {
        return context.inputLimit();
    }

    public void recordStats(UUID groupId, UUID runId, ContextBundle bundle) {
        context.recordStats(groupId, runId, bundle);
    }

    public Map<String, Object> contextStats(UUID groupId, UUID runId) {
        return context.contextStats(groupId, runId);
    }

    public Map<String, Object> latestContextStats(UUID groupId) {
        return context.latestContextStats(groupId);
    }

    public String context(UUID groupId, UUID messageId) {
        return context.build(groupId, messageId);
    }

    public List<MemorySource> search(UUID groupId, String question, int limit) {
        return search(groupId, question, limit, null);
    }

    public List<MemorySource> search(UUID groupId, String question, int limit, UUID exclude) {
        List<MemorySource> found = markdown.search(groupId, question, limit, exclude);
        if (!properties.ragEnabled()) {
            return found;
        }
        List<MemorySource> result = new ArrayList<>(found);
        try {
            for (MemorySource source : vectors.search(groupId, question, limit, exclude)) {
                if (result.stream().noneMatch(item -> item.id().equals(source.id()))) {
                    result.add(source);
                }
            }
        } catch (Exception failure) {
            log.warn(
                    "VECTOR_SEARCH_UNAVAILABLE group={} exception={}，继续使用 Markdown 搜索",
                    groupId,
                    failure.getClass().getSimpleName());
        }
        return result.stream().limit(Math.min(10, Math.max(1, limit))).toList();
    }
}
