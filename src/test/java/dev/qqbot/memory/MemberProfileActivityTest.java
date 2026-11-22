/**
 * [INPUT]: 使用固定时钟和按时间排序的发言。
 * [OUTPUT]: 验证冷启动准入与 30 天、90 天统计各自的边界。
 * [POS]: 画像活跃统计的纯逻辑测试。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.qqbot.memory.MemberProfileActivity.Candidate;
import dev.qqbot.memory.MemberProfileActivity.Message;
import dev.qqbot.memory.MemberProfileActivity.Window;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemberProfileActivityTest {
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final Window WINDOW = Window.endingAt(NOW, 120);
    private static final String SENDER = "member-1";

    @Test
    void coldStartUsesOlderMessagesOnlyForAdmission() {
        var messages =
                List.of(
                        new Message("旧名", "旧版配置", NOW.minus(Duration.ofDays(91))),
                        new Message("前名", "调整配置", WINDOW.quarter()),
                        new Message("现名", "读取规则", WINDOW.month()),
                        new Message("现名", "保存规则", WINDOW.month().plusSeconds(60)),
                        new Message("噪声名", "/profile status", NOW));

        var result =
                MemberProfileActivity.summarize(WINDOW, new Candidate(SENDER, 0, true), messages);

        assertEquals(4, result.admitted());
        assertEquals(3, result.admissionDays());
        assertEquals(List.of("现名", "前名"), result.aliases());
        assertEquals(WINDOW.month().plusSeconds(60), result.lastActive());
        assertEquals(
                new MemberProfileActivity.Metrics(2, 3, 1, List.of(0, 0, 0, 2), 4.0, 1.0),
                result.metrics());
    }

    @Test
    void establishedProfileUsesOnlyMonthlyMessagesForAdmission() {
        var messages = List.of(new Message(SENDER, "调整配置", WINDOW.quarter()));

        var result =
                MemberProfileActivity.summarize(WINDOW, new Candidate(SENDER, 0, false), messages);

        assertEquals(0, result.admitted());
        assertEquals(0, result.admissionDays());
        assertEquals("成员", result.displayName());
        assertEquals(0, result.metrics().messages30d());
        assertEquals(1, result.metrics().messages90d());
    }
}
