/**
 * [INPUT]: 使用冻结会话来源、重复证据和可选画像。
 * [OUTPUT]: 验证组装输入快照、引用编号与查证模式隔离。
 * [POS]: 候选排序、预算及提示渲染的纯路径回归。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.MemoryProperties;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContextCompositionTest {
    private static final UUID GROUP = new UUID(0, 1);
    private static final UUID CONVERSATION = new UUID(0, 2);
    private static final UUID MESSAGE = new UUID(0, 3);
    private static final Instant NOW = Instant.parse("2026-11-22T03:00:00Z");
    private static final String SENDER = "sender-a";
    private static final String MESSAGE_KEY = "message:" + MESSAGE + ":0";
    private static final String MESSAGE_TEXT = "周一交付可复用的压测报告";

    private final ContextAssembler assembler =
            new ContextAssembler(MemoryProperties.defaults(), new ObjectMapper());

    @Test
    void assemble_reusesHistoryCitationAndRetainsStandaloneRank() {
        var history =
                new HistoryEvent(
                        MESSAGE_KEY,
                        1,
                        NOW,
                        Role.USER,
                        SENDER,
                        "测试成员",
                        MESSAGE_TEXT,
                        CONVERSATION,
                        0L,
                        null,
                        true);
        Evidence raw =
                new Evidence(MESSAGE_KEY, SourceKind.RAW, MESSAGE_TEXT, List.of(MESSAGE), NOW, 1);
        Evidence duplicate =
                new Evidence(
                        "vector:duplicate",
                        SourceKind.VECTOR,
                        MESSAGE_TEXT,
                        List.of(MESSAGE),
                        NOW,
                        1);
        Evidence manual =
                new Evidence("manual:standalone", SourceKind.MANUAL, "归档后验证耗时", List.of(), NOW, 1);
        var source = new ArrayList<>(List.of(raw, duplicate, manual));
        var inputs = new ContextAssembler.Inputs("", List.of(history), source, "");
        source.clear();

        ContextBundle result = assembler.assemble(request(QueryMode.HISTORY), inputs);

        assertEquals(List.of(raw, manual), result.evidence());
        assertEquals(List.of(duplicate.sourceKey()), result.stats().droppedKeys());
        assertEquals(List.of("DUPLICATE_SOURCE"), result.stats().reasons());
        assertEquals(CitationMode.REQUIRED, result.citationMode());
        assertTrue(result.userPrompt().contains("\"sources\":[\"[S1]\"]"));
        assertTrue(result.userPrompt().contains("[S2] MANUAL"));
        assertFalse(result.userPrompt().contains("[S1] RAW"));
    }

    @Test
    void assemble_dropsProfileEvenWhenCallerSuppliesItForHistory() {
        var inputs = new ContextAssembler.Inputs("", List.of(), List.of(), "");
        var profile =
                new MemberProfileContext.Selection(
                        true, true, List.of(new MemberProfileContext.Card(SENDER, "示例", "偏好直接交流")));
        ContextRequest request = request(QueryMode.HISTORY);

        assertEquals(
                assembler.assemble(request, inputs), assembler.assemble(request, inputs, profile));
    }

    private static ContextRequest request(QueryMode mode) {
        var scope = new MemoryScope(GROUP, 0, MESSAGE, 2, NOW, NOW);
        return new ContextRequest(scope, CONVERSATION, 0, SENDER, "有哪些安排？", mode);
    }
}
