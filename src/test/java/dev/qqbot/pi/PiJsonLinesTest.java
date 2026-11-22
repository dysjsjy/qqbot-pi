/**
 * [INPUT]: 使用助手帧、兼容提示响应和缺失计量字段的固定 JSON。
 * [OUTPUT]: 验证文本块合并、提示拒绝及用量状态。
 * [POS]: Pi JSON 边界的纯转换回归；不启动子进程。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.pi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.model.ModelUsage;
import dev.qqbot.model.UsageStatus;
import dev.qqbot.pi.PiJsonLines.Answer;
import dev.qqbot.pi.PiJsonLines.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PiJsonLinesTest {
    private final PiJsonLines protocol = new PiJsonLines(new ObjectMapper());

    @Test
    void readFrame_preservesUtf8AndReportsEndOfStream() throws IOException {
        byte[] bytes = "中文🙂\r\n".getBytes(StandardCharsets.UTF_8);
        try (var input = new ByteArrayInputStream(bytes)) {
            assertEquals("中文🙂", PiJsonLines.readFrame(input).orElseThrow());
            assertTrue(PiJsonLines.readFrame(input).isEmpty());
        }
    }

    @Test
    void decodeEvent_projectsTextAndReportedUsageFromAssistantMessage() throws IOException {
        Answer answer =
                decodeAnswer(
                        """
                {"role":"assistant", "content":[
                    {"type":"text", "text":"输出"},
                    {"type":"thinking", "thinking":"不公开"},
                    {"type":"text", "text":"完成"}
                ],"usage":{"input":12,"output":4,"cacheRead":0,"cacheWrite":2,"reasoning":3}}
                """);

        assertEquals("输出完成", answer.text());
        assertEquals(new ModelUsage(12L, 4L, 0L, 2L, 3L, UsageStatus.REPORTED), answer.usage());
    }

    @Test
    void decodeEvent_marksIncompleteUsageAndAbortedAnswer() throws IOException {
        Answer answer =
                decodeAnswer(
                        """
                {"role":"assistant","stopReason":"aborted","usage":{"input":7,"output":-1}}
                """);

        assertTrue(answer.isFailed());
        assertEquals("", answer.text());
        assertEquals(
                new ModelUsage(7L, null, null, null, null, UsageStatus.UNKNOWN), answer.usage());
    }

    @Test
    void decodeEvent_doesNotTreatAllZeroUsageAsReported() throws IOException {
        Answer answer =
                decodeAnswer(
                        """
                {"role":"assistant","usage":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0}}
                """);

        assertEquals(ModelUsage.unknown(), answer.usage());
    }

    @Test
    void decodeEvent_defaultsMissingDispositionButRejectsAnExplicitNonStartedResponse()
            throws IOException {
        Response legacy = decodeResponse("{}");
        Response rejected = decodeResponse("{\"disposition\":\"queued\"}");

        assertTrue(legacy.isStarted());
        assertFalse(rejected.isStarted());
    }

    private Answer decodeAnswer(String message) throws IOException {
        return assertInstanceOf(
                Answer.class,
                protocol.decodeEvent("{\"type\":\"message_end\",\"message\":" + message + "}"));
    }

    private Response decodeResponse(String data) throws IOException {
        return assertInstanceOf(
                Response.class,
                protocol.decodeEvent(
                        "{\"type\":\"response\",\"id\":\"run\",\"success\":true,\"data\":"
                                + data
                                + "}"));
    }
}
