/**
 * [INPUT]: 依赖 Jackson 和 Pi 子进程的 UTF-8 JSONL。
 * [OUTPUT]: 读写 RPC 帧，把输出转换为有类型的事件及模型用量。
 * [POS]: Pi 协议的 JSON 边界；不决定何时接受结果或推进时间预算。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.pi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.model.ModelUsage;
import dev.qqbot.model.UsageStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

final class PiJsonLines {
    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    private static final String TYPE = "type";
    private static final String ASSISTANT = "assistant";
    private static final String STARTED = "started";
    private static final String DATA = "data";
    private static final String USAGE = "usage";

    private final ObjectMapper json;

    PiJsonLines(ObjectMapper json) {
        this.json = json;
    }

    static Optional<String> readFrame(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1 && next != '\n') {
            if (bytes.size() >= MAX_FRAME_BYTES) {
                throw new IOException("FRAME_TOO_LARGE");
            }
            bytes.write(next);
        }
        if (next == -1 && bytes.size() == 0) {
            return Optional.empty();
        }
        String frame = bytes.toString(StandardCharsets.UTF_8);
        return Optional.of(frame.endsWith("\r") ? frame.substring(0, frame.length() - 1) : frame);
    }

    void send(Process child, Object command) throws IOException {
        synchronized (child) {
            var output = child.getOutputStream();
            byte[] bytes =
                    (json.writeValueAsString(command) + "\n").getBytes(StandardCharsets.UTF_8);
            // SIDE EFFECT: 与取消请求串行写入标准输入，避免两条帧交错。
            output.write(bytes);
            output.flush();
        }
    }

    Event decodeEvent(String frame) throws IOException {
        JsonNode event = json.readTree(frame);
        return switch (event.path(TYPE).asText()) {
            case "qqbot_runtime_usage" -> resources(event);
            case "qqbot_runtime_ready" -> new Ready(event.path("maxOutputTokens").asInt(-1));
            case "response" -> response(event);
            case "message_update" -> progress(event);
            case "message_end" -> answer(event.path("message"));
            case "agent_settled" -> new Settled(event.path("aborted").asBoolean());
            default -> Ignored.EVENT;
        };
    }

    private static Resources resources(JsonNode event) {
        long cpuMicros = event.path("cpuUserUs").asLong(0) + event.path("cpuSystemUs").asLong(0);
        return new Resources(
                cpuMicros, event.path("rssBytes").asLong(0), event.path("heapUsedBytes").asLong(0));
    }

    private static Response response(JsonNode event) {
        JsonNode data = event.path(DATA);
        boolean isStarted =
                event.path("success").asBoolean()
                        && STARTED.equals(data.path("disposition").asText(STARTED));
        return new Response(
                event.path("id").asText(), isStarted, data.path("sessionFile").asText(null));
    }

    private static Event progress(JsonNode event) {
        JsonNode message = event.path("message");
        if (!isAssistant(message)) {
            return Ignored.EVENT;
        }
        JsonNode update = event.path("assistantMessageEvent");
        String type = update.path(TYPE).asText();
        boolean hasContent =
                ("text_delta".equals(type) || "thinking_delta".equals(type))
                        && !update.path("delta").asText().isBlank();
        return new Progress(hasContent, readUsage(message.path(USAGE)));
    }

    private static Event answer(JsonNode message) {
        if (!isAssistant(message)) {
            return Ignored.EVENT;
        }
        String reason = message.path("stopReason").asText();
        boolean isFailed = "error".equals(reason) || "aborted".equals(reason);
        String text = isFailed ? "" : readText(message.path("content"));
        return new Answer(text, isFailed, readUsage(message.path(USAGE)));
    }

    private static boolean isAssistant(JsonNode message) {
        return ASSISTANT.equals(message.path("role").asText());
    }

    private static String readText(JsonNode content) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path(TYPE).asText())) {
                text.append(block.path("text").asText());
            }
        }
        return text.toString();
    }

    private static ModelUsage readUsage(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        Long input = count(node, "input");
        Long output = count(node, "output");
        Long cacheRead = count(node, "cacheRead");
        Long cacheWrite = count(node, "cacheWrite");
        Long reasoning = count(node, "reasoning");
        boolean isComplete = hasBillableCounts(input, output, cacheRead, cacheWrite);
        if (isComplete && areZero(input, output, cacheRead, cacheWrite)) {
            return ModelUsage.unknown();
        }
        UsageStatus status = isComplete ? UsageStatus.REPORTED : UsageStatus.UNKNOWN;
        return new ModelUsage(input, output, cacheRead, cacheWrite, reasoning, status);
    }

    private static boolean hasBillableCounts(
            Long input, Long output, Long cacheRead, Long cacheWrite) {
        return input != null && output != null && cacheRead != null && cacheWrite != null;
    }

    private static boolean areZero(long input, long output, long cacheRead, long cacheWrite) {
        return input == 0 && output == 0 && cacheRead == 0 && cacheWrite == 0;
    }

    private static Long count(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0
                ? value.longValue()
                : null;
    }

    sealed interface Event permits Resources, Ready, Response, Progress, Answer, Settled, Ignored {}

    record Resources(long cpuMicros, long rssBytes, long heapBytes) implements Event {}

    record Ready(int maxOutputTokens) implements Event {}

    record Response(String id, boolean isStarted, String sessionFile) implements Event {}

    record Progress(boolean hasContent, ModelUsage usage) implements Event {}

    record Answer(String text, boolean isFailed, ModelUsage usage) implements Event {}

    record Settled(boolean isAborted) implements Event {}

    enum Ignored implements Event {
        EVENT
    }
}
