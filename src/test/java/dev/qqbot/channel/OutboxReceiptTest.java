/**
 * [INPUT]: 依赖投递端口结果及当前尝试次数。
 * [OUTPUT]: 验证状态推导与重试上限。
 * [POS]: 出站规则的纯逻辑测试。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.qqbot.delivery.MessageTransport.State;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OutboxReceiptTest {
    @ParameterizedTest
    @CsvSource({
        "ACCEPTED,1,SENT",
        "UNKNOWN,1,UNKNOWN",
        "FAILED,1,FAILED",
        "RETRYABLE,2,PENDING",
        "RETRYABLE,3,FAILED"
    })
    void stateAfterDelivery_obeysReceiptAndAttemptLimit(
            State receipt, int attempts, String outcome) {
        assertEquals(outcome, OutboxWorker.stateAfterDelivery(receipt, attempts));
    }
}
