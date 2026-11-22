/**
 * [INPUT]: 提供有序原文和根据提示长度设定的预算。
 * [OUTPUT]: 验证刚好容纳一条、第一条超限及来源快照。
 * [POS]: 摘要入队的纯规则测试。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static org.junit.jupiter.api.Assertions.*;

import dev.qqbot.memory.MarkdownSummaryText.Source;
import dev.qqbot.model.TokenEstimator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarkdownSummaryPreparationTest {
    @Test
    void selectSources_keepsOrderedPrefixWithinBudget() {
        var first = source("首条完整消息");
        var second = source("下一条🙂");
        var candidates = new ArrayList<>(List.of(first, second));

        var selection = MarkdownSummaryPreparation.selectSources(candidates, inputTokens(first));
        candidates.clear();

        assertEquals(List.of(first), selection.sources());
        assertFalse(selection.isOversized());
    }

    @Test
    void selectSources_keepsOversizedFirstSourceForFailureRecord() {
        var first = source("超过预算");
        var selection =
                MarkdownSummaryPreparation.selectSources(List.of(first), inputTokens(first) - 1);

        assertEquals(List.of(first), selection.sources());
        assertTrue(selection.isOversized());
    }

    private static Source source(String body) {
        return new Source(UUID.randomUUID(), Instant.EPOCH, "成员", body);
    }

    private static int inputTokens(Source source) {
        StringBuilder body = new StringBuilder();
        MarkdownSummaryText.appendSource(body, source);
        return TokenEstimator.estimateInput(
                MarkdownSummaryText.SYSTEM, MarkdownSummaryText.V2_INSTRUCTION + body);
    }
}
