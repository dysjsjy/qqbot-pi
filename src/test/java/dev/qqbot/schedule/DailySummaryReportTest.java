/**
 * [INPUT]: 使用合成段落、选材与冻结 UUID。
 * [OUTPUT]: 验证终稿引用、选材覆盖与闲聊占位。
 * [POS]: 日报纯渲染测试，不调用生成端口或平台。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static org.junit.jupiter.api.Assertions.*;

import dev.qqbot.schedule.DailySummaryFacts.Paragraph;
import dev.qqbot.schedule.DailySummaryFacts.WriteResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DailySummaryReportTest {
    private static final String SOURCE = "00000000-0000-0000-0000-000000000001";

    @Test
    void renderReport_omitsLeadForASingleTopicAndRetainsItsCitation() {
        String report = DailySummaryReport.renderReport(draft(), List.of("fact1"), Set.of(SOURCE));

        assertEquals("已决定版本范围 [" + SOURCE + "]", report);
    }

    @Test
    void renderReport_rejectsUncoveredSelection() {
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                DailySummaryReport.renderReport(
                                        draft(), List.of("fact2"), Set.of(SOURCE)));

        assertEquals("SUMMARY_REQUIRED_FACT_MISSING", failure.getMessage());
    }

    @Test
    void renderSmallTalk_preservesFirstSourceForAnOtherwiseEmptyReport() {
        assertTrue(
                DailySummaryReport.renderSmallTalk(SOURCE, Set.of(SOURCE))
                        .endsWith("[" + SOURCE + "]"));
    }

    private static WriteResult draft() {
        return new WriteResult(
                "不应显示的导语", List.of(new Paragraph("已决定版本范围", List.of("fact1"), Set.of(SOURCE))));
    }
}
