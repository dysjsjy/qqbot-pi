/**
 * [INPUT]: 依赖 QQ v2 网关帧、Jackson 与已建立的 WebSocket。
 * [OUTPUT]: 提供 Hello 和 READY 校验、事件序号读取及认证和心跳帧。
 * [POS]: 官方网关 JSON 边界。连接世代、重放水位与定时器由调用方维护。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.channel;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class OfficialQqGatewayProtocol {
    private static final String DATA = "d";
    private static final String SESSION_ID = "session_id";
    private static final long MIN_HEARTBEAT_MILLIS = 100;
    private static final long MAX_HEARTBEAT_MILLIS = 300_000;
    private static final int SEND_TIMEOUT_SECONDS = 5;
    private static final int GROUP_AND_PRIVATE_MESSAGES = 1 << 25;
    private final ObjectMapper json;

    OfficialQqGatewayProtocol(ObjectMapper json) {
        this.json = json;
    }

    JsonNode decode(String raw) throws JsonProcessingException {
        return json.readTree(raw);
    }

    static int opcode(JsonNode packet) {
        return packet.path("op").asInt(-1);
    }

    static String eventType(JsonNode packet) {
        return packet.path("t").asText("");
    }

    static Optional<Long> sequence(JsonNode packet) {
        JsonNode sequence = packet.path("s");
        return sequence.isIntegralNumber() ? Optional.of(sequence.asLong()) : Optional.empty();
    }

    static long heartbeatInterval(JsonNode packet) {
        JsonNode data = packet.path(DATA);
        long interval = data.path("heartbeat_interval").asLong(0);
        if (interval < MIN_HEARTBEAT_MILLIS || interval > MAX_HEARTBEAT_MILLIS) {
            throw new IllegalStateException("QQ_WS_INVALID_HELLO");
        }
        return interval;
    }

    static Ready readReady(JsonNode packet) {
        JsonNode data = packet.path(DATA);
        String session = data.path(SESSION_ID).asText("");
        if (session.isBlank()) {
            throw new IllegalStateException("QQ_WS_INVALID_READY");
        }
        return new Ready(session, data.path("user"));
    }

    void authenticate(WebSocket socket, String credential, String session, Long sequence)
            throws Exception {
        String token = "QQBot " + credential;
        if (session.isBlank()) {
            send(
                    socket,
                    new Request(2, new Identify(token, GROUP_AND_PRIVATE_MESSAGES, List.of(0, 1))));
            return;
        }
        send(socket, new Request(6, new Resume(token, session, sequence == null ? 0 : sequence)));
    }

    void heartbeat(WebSocket socket, Long sequence) throws Exception {
        send(socket, new Request(1, sequence));
    }

    private void send(WebSocket socket, Request request) throws Exception {
        if (socket == null) {
            throw new IllegalStateException("QQ_WS_NOT_CONNECTED");
        }
        String encoded = json.writeValueAsString(request);
        // SIDE EFFECT: 等待网关发送完成，超时由连接状态机重连。
        socket.sendText(encoded, true).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    record Ready(String sessionId, JsonNode user) {}

    private record Request(@JsonProperty("op") int opcode, @JsonProperty(DATA) Object data) {}

    private record Identify(String token, int intents, List<Integer> shard) {}

    private record Resume(String token, @JsonProperty(SESSION_ID) String session, long seq) {}
}
