/**
 * [INPUT]: 提供任务创建时间、当前时间和请求预算。
 * [OUTPUT]: 验证排队计入截止时间，未来创建时间不扩大预算。
 * [POS]: 回复执行器的纯时间规则测试。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.message;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentWorkerBudgetTest {
    @Test
    void remainingSeconds_chargesQueueTimeAndCapsFutureTimestamp() {
        assertEquals(
                3, AgentWorker.remainingSeconds(Instant.EPOCH, Instant.EPOCH.plusSeconds(7), 10));
        assertEquals(
                10, AgentWorker.remainingSeconds(Instant.EPOCH.plusSeconds(1), Instant.EPOCH, 10));
    }

    @Test
    void remainingSeconds_rejectsLessThanOneSecond() {
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                AgentWorker.remainingSeconds(
                                        Instant.EPOCH, Instant.EPOCH.plusMillis(9_001), 10));
        assertEquals("REQUEST_TIMEOUT", failure.getMessage());
    }
}
