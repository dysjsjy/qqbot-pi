/**
 * [INPUT]: 提供固定业务日期、快照时刻和消息。
 * [OUTPUT]: 验证业务日边界及消息数、正文码点限制。
 * [POS]: 日报认领的纯规则测试，不读取数据库。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import dev.qqbot.schedule.DailySummaryFacts.SourceMessage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DailySummaryPreparationTest {
    @Test
    void businessWindow_usesShanghaiMidnightAndKeepsSnapshotClock() {
        Instant capturedAt = Instant.parse("2026-11-22T02:00:00Z");
        var window = DailySummaryPreparation.businessWindow(LocalDate.of(2026, 11, 21), capturedAt);

        assertThat(window.from()).isEqualTo(Instant.parse("2026-11-20T16:00:00Z"));
        assertThat(window.to()).isEqualTo(Instant.parse("2026-11-21T16:00:00Z"));
        assertThat(window.snapshotAt()).isEqualTo(capturedAt);
    }

    @Test
    void exceedsLimit_countsMessagesAndUnicodeCodePoints() {
        var maximumText = source("🙂".repeat(100_000));
        var shortText = source("群聊");

        assertThat(DailySummaryPreparation.exceedsLimit(List.of(maximumText))).isFalse();
        assertThat(DailySummaryPreparation.exceedsLimit(List.of(maximumText, shortText))).isTrue();
        assertThat(DailySummaryPreparation.exceedsLimit(Collections.nCopies(1_000, shortText)))
                .isFalse();
        assertThat(DailySummaryPreparation.exceedsLimit(Collections.nCopies(1_001, shortText)))
                .isTrue();
    }

    private static SourceMessage source(String text) {
        return new SourceMessage(UUID.randomUUID().toString(), "2026-11-21T00:00:00Z", "成员", text);
    }
}
