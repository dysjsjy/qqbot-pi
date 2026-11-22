/**
 * [INPUT]: 使用合成日报消息、平台占位与补充字符。
 * [OUTPUT]: 验证来源偏移、快照不可变与输入失败路径。
 * [POS]: 日报证据快照纯函数测试；不调用模型。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static org.junit.jupiter.api.Assertions.*;

import dev.qqbot.schedule.DailySummaryFacts.SourceMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DailySummarySourcesTest {
    @Test
    void freeze_preservesCodePointOffsets_whenOneMessageSpansTwoUnits() {
        var messages = new ArrayList<>(List.of(source("🗨".repeat(601)), source("<@unknown>")));
        var frozen = DailySummarySources.freezeSources(messages, Map.of());
        messages.clear();

        assertEquals(List.of("u0", "u0_600", "u1"), List.copyOf(frozen.units().keySet()));
        assertEquals(600, frozen.units().get("u0").end());
        assertEquals(600, frozen.units().get("u0_600").start());
        assertEquals("🗨", frozen.units().get("u0_600").text());
        assertEquals(List.of("u1"), List.copyOf(frozen.markupOnlyUnits()));
        assertTrue(frozen.forbiddenSpeakers().contains("示例昵称"));
        assertThrows(UnsupportedOperationException.class, () -> frozen.units().clear());
    }

    @Test
    void freeze_rejectsDuplicateSourceIds() {
        SourceMessage repeated = source("需要完成的事项");
        var error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                DailySummarySources.freezeSources(
                                        List.of(repeated, repeated), Map.of()));
        assertEquals("SUMMARY_WORKFLOW_INVARIANT", error.getMessage());
        assertTrue(error.getCause().getMessage().contains(repeated.messageId()));
    }

    @Test
    void freeze_rejectsInputBeyondCodePointBudget() {
        var error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                DailySummarySources.freezeSources(
                                        List.of(source("日".repeat(100_001))), Map.of()));
        assertEquals("INPUT_LIMIT", error.getMessage());
    }

    @Test
    void bisectSource_preservesCitationAndUnicodeBoundaries() {
        SourceMessage message = source("🗨甲乙");
        var frozen = DailySummarySources.freezeSources(List.of(message), Map.of());

        var split = DailySummarySources.bisectSource(frozen.units().get("u0"));

        assertEquals("🗨", split.left().text());
        assertEquals("甲乙", split.right().text());
        assertEquals(1, split.left().end());
        assertEquals(split.left().end(), split.right().start());
        assertEquals(message.messageId(), split.left().messageId());
        assertEquals(message.messageId(), split.right().messageId());
    }

    @Test
    void bisectSource_rejectsSingleCodePoint() {
        var frozen = DailySummarySources.freezeSources(List.of(source("🗨")), Map.of());

        var error =
                assertThrows(
                        IllegalStateException.class,
                        () -> DailySummarySources.bisectSource(frozen.units().get("u0")));

        assertEquals("CONTEXT_TOO_LARGE", error.getMessage());
    }

    private static SourceMessage source(String text) {
        return new SourceMessage(
                UUID.randomUUID().toString(), "2026-11-21T08:00:00Z", "示例昵称", text);
    }
}
