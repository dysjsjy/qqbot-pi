/**
 * [INPUT]: 接收运行片段、资源计数与已筛选的属性。
 * [OUTPUT]: 提供持久化和 HTTP 查询共用的链路片段。
 * [POS]: 片段的字段契约。未报告的用量以 null 表示。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record SpanRecord(
        UUID id,
        UUID runId,
        UUID groupId,
        String parentSpan,
        String kind,
        String name,
        int seq,
        String status,
        String errorCode,
        long startUs,
        long durationUs,
        long cpuUs,
        long allocBytes,
        long memoryBytes,
        Long estimatedInputTokens,
        Long inputTokens,
        Long outputTokens,
        Long cacheReadTokens,
        Long cacheWriteTokens,
        Long reasoningTokens,
        Map<String, Object> attributes) {}
