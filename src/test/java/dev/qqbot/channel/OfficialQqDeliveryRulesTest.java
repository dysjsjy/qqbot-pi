/**
 * [INPUT]: 提供固定时钟、回复上下文和官方消息回执。
 * [OUTPUT]: 验证回复窗口、未知结果和提前刷新规则。
 * [POS]: QQ 出站纯测试，不访问平台。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.channel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.qqbot.channel.OfficialQqHttp.Receipt;
import dev.qqbot.delivery.MessageTransport.Delivery;
import dev.qqbot.delivery.MessageTransport.Reply;
import dev.qqbot.delivery.MessageTransport.State;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OfficialQqDeliveryRulesTest {
    private static final Instant NOW = Instant.parse("2026-11-22T04:30:00Z");
    private static final String TARGET = "group-id";
    private static final String MESSAGE = "message-id";

    @Test
    void validateDelivery_allowsOnlyRepliesBeforeFiveMinutesAndAtMostFivePerMessage() {
        assertThat(
                        OfficialQqClient.validateDelivery(
                                TARGET, new Reply(MESSAGE, NOW.minusSeconds(299), 5), false, NOW))
                .isEmpty();
        assertThat(
                        OfficialQqClient.validateDelivery(
                                TARGET, new Reply(MESSAGE, NOW.minusSeconds(300), 5), false, NOW))
                .contains("QQ_REPLY_EXPIRED");
        assertThat(OfficialQqClient.validateDelivery(TARGET, new Reply(MESSAGE, NOW, 6), true, NOW))
                .contains("QQ_REPLY_LIMIT");
    }

    @Test
    void classifyReceipt_neverConfirmsDuplicateOrMissingReceipt() {
        assertThat(OfficialQqClient.classifyReceipt(200, new Receipt(40_054_005, "")))
                .isEqualTo(new Delivery(State.UNKNOWN, null, "QQ_DUPLICATE_RECEIPT_UNKNOWN"));
        assertThat(OfficialQqClient.classifyReceipt(200, new Receipt(0, "")))
                .isEqualTo(new Delivery(State.UNKNOWN, null, "QQ_MISSING_RECEIPT"));
        assertThat(OfficialQqClient.classifyReceipt(200, new Receipt(0, MESSAGE)))
                .isEqualTo(new Delivery(State.ACCEPTED, MESSAGE, null));
    }

    @Test
    void refreshAt_clampsRefreshMarginAndKeepsAtLeastOneSecond() {
        assertThat(OfficialQqClient.refreshAt(NOW, 1)).isEqualTo(NOW.plusSeconds(1));
        assertThat(OfficialQqClient.refreshAt(NOW, 100)).isEqualTo(NOW.plusSeconds(90));
        assertThat(OfficialQqClient.refreshAt(NOW, 800)).isEqualTo(NOW.plusSeconds(740));
    }
}
