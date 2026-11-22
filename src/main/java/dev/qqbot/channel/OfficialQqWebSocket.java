/**
 * [INPUT]: 依赖官方网关地址、令牌、帧编解码和 QQ 事件写入。
 * [OUTPUT]: 提供长连接、心跳、重放和诊断状态。
 * [POS]: 在单线程执行器上串行维护连接世代。监听器只提交帧和连接信号。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.channel;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import dev.qqbot.BotProperties;
import jakarta.annotation.PreDestroy;
import java.net.http.*;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.CompletionStage;
import org.slf4j.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OfficialQqWebSocket {
    private static final Logger log = LoggerFactory.getLogger(OfficialQqWebSocket.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final int HANDSHAKE_TIMEOUT_SECONDS = 20;
    private static final int MAX_BACKOFF_SECONDS = 60;
    private static final int MAX_BACKOFF_EXPONENT = 6;
    private final BotProperties props;
    private final OfficialQqGatewayProtocol protocol;
    private final OfficialQqEvents events;
    private final OfficialQqClient client;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(OfficialQqWebSocket::daemonThread);
    private volatile boolean stopped;
    private volatile String state = "DISABLED";
    private volatile String error = "";
    private volatile Instant connectedAt;
    private volatile Instant lastEventAt;
    private volatile Instant lastHeartbeatAt;
    private volatile int reconnects;
    private volatile WebSocket socket;
    private ScheduledFuture<?> heartbeat;
    private ScheduledFuture<?> watchdog;
    private ScheduledFuture<?> retry;
    private long generation;
    private Long sequence;
    private String session = "";
    private String credential;
    private int failures;
    private boolean waitingAck;
    private boolean refreshToken;

    public OfficialQqWebSocket(
            BotProperties props,
            ObjectMapper json,
            OfficialQqEvents events,
            OfficialQqClient client) {
        this.props = props;
        this.protocol = new OfficialQqGatewayProtocol(json);
        this.events = events;
        this.client = client;
        if (props.qq().enabled()) {
            state = websocket() ? "STARTING" : "WEBHOOK";
        }
    }

    private static Thread daemonThread(Runnable task) {
        Thread thread = new Thread(task, "qq-official-gateway");
        thread.setDaemon(true);
        return thread;
    }

    private boolean websocket() {
        return "websocket".equals(props.qq().official().transport());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (props.qq().enabled() && websocket()) {
            execute(this::connect);
        }
    }

    public Status status() {
        return new Status(
                props.qq().official().transport(),
                state,
                error,
                connectedAt,
                lastEventAt,
                lastHeartbeatAt,
                reconnects);
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Status(
            String transport,
            String state,
            String error,
            Instant connectedAt,
            Instant lastEventAt,
            Instant lastHeartbeatAt,
            int reconnects) {}

    private void execute(Runnable task) {
        if (stopped) {
            return;
        }
        try {
            worker.execute(() -> runWhileActive(task));
        } catch (RejectedExecutionException failure) {
            if (!stopped) {
                throw failure;
            }
        }
    }

    private void runWhileActive(Runnable task) {
        if (!stopped) {
            task.run();
        }
    }

    private void connect() {
        if (stopped) {
            return;
        }
        state = "CONNECTING";
        long attempt = ++generation;
        log.info("QQ_WS_CONNECT 正在连接官方机器人网关");
        try {
            credential = client.gatewayToken(refreshToken);
            refreshToken = false;
            var uri = client.gateway();
            credential = client.gatewayToken(false);
            beginHandshake(uri, attempt);
        } catch (Exception failure) {
            reconnectAfterDiscoveryFailure(failure);
        }
    }

    private void beginHandshake(java.net.URI uri, long attempt) {
        // SIDE EFFECT: 凭据只在 Hello 之后写入 RPC 数据，避免进入 URL 或升级请求头。
        var connection = http.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT);
        connection
                .buildAsync(uri, new SocketListener(attempt))
                .whenComplete((opened, failure) -> handshakeCompleted(attempt, opened, failure));
        watchdog =
                worker.schedule(
                        () -> disconnectIfCurrent(attempt, "QQ_WS_HANDSHAKE_TIMEOUT", false),
                        HANDSHAKE_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS);
    }

    private void handshakeCompleted(long attempt, WebSocket opened, Throwable failure) {
        if (stopped && opened != null) {
            opened.abort();
        } else if (failure != null) {
            socketLost(attempt, "QQ_WS_CONNECT_FAILED", false);
        }
    }

    private void reconnectAfterDiscoveryFailure(Exception failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        String code = failure.getMessage();
        disconnect(
                code != null && code.matches("QQ_[A-Z0-9_]+") ? code : "QQ_GATEWAY_UNAVAILABLE",
                false);
    }

    private void receive(long attempt, String raw) {
        try {
            JsonNode packet = protocol.decode(raw);
            int op = OfficialQqGatewayProtocol.opcode(packet);
            switch (op) {
                case 10 ->
                        authenticate(attempt, OfficialQqGatewayProtocol.heartbeatInterval(packet));
                case 0 -> acceptDispatch(packet);
                case 1 -> beat();
                case 11 -> acknowledgeHeartbeat();
                case 7 -> disconnect("QQ_WS_SERVER_RECONNECT", false);
                case 9 -> disconnect("QQ_WS_INVALID_SESSION", true);
                default -> log.debug("QQ_WS_IGNORED_OPCODE op={}", op);
            }
        } catch (Exception failure) {
            log.warn(
                    "QQ_WS_EVENT_FAILED 事件处理失败，等待重连重放；exception={}",
                    failure.getClass().getSimpleName());
            disconnect("QQ_WS_EVENT_FAILED", false);
        }
    }

    private void authenticate(long attempt, long interval) throws Exception {
        state = "AUTHENTICATING";
        protocol.authenticate(socket, credential, session, sequence);
        cancel(heartbeat);
        waitingAck = false;
        heartbeat =
                worker.scheduleAtFixedRate(
                        () -> checkHeartbeat(attempt), interval, interval, TimeUnit.MILLISECONDS);
    }

    private void checkHeartbeat(long attempt) {
        if (attempt != generation) {
            return;
        }
        if (waitingAck) {
            disconnect("QQ_WS_HEARTBEAT_TIMEOUT", false);
            return;
        }
        try {
            beat();
        } catch (Exception failure) {
            disconnect("QQ_WS_SEND_FAILED", false);
        }
    }

    private void acceptDispatch(JsonNode packet) {
        switch (OfficialQqGatewayProtocol.eventType(packet)) {
            case "READY" -> acceptReady(packet);
            case "RESUMED" -> online();
            default -> {
                events.dispatch(packet, "websocket");
                lastEventAt = Instant.now();
            }
        }
        // SIDE EFFECT: 事务提交后才前移重放水位，否则恢复连接必须重放这条事件。
        sequence = OfficialQqGatewayProtocol.sequence(packet).orElse(sequence);
    }

    private void acceptReady(JsonNode packet) {
        var ready = OfficialQqGatewayProtocol.readReady(packet);
        session = ready.sessionId();
        events.identifyBot(ready.user());
        online();
    }

    private void acknowledgeHeartbeat() {
        waitingAck = false;
        lastHeartbeatAt = Instant.now();
    }

    private void online() {
        state = "ONLINE";
        error = "";
        connectedAt = Instant.now();
        failures = 0;
        cancel(watchdog);
        log.info("QQ_WS_ONLINE 官方机器人已连接，可私聊或在群中 @ 测试；首次发现群需启用采集和回复");
    }

    private void beat() throws Exception {
        protocol.heartbeat(socket, sequence);
        waitingAck = true;
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    private void disconnect(String code, boolean reset) {
        if (stopped) {
            return;
        }
        ++generation;
        error = code;
        state = "RECONNECTING";
        reconnects++;
        cancel(heartbeat);
        cancel(watchdog);
        if (socket != null) {
            socket.abort();
            socket = null;
        }
        if (reset) {
            session = "";
            sequence = null;
            refreshToken = true;
        }
        long delay =
                Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(failures++, MAX_BACKOFF_EXPONENT));
        log.warn("{}，{} 秒后重连。检查应用凭据、平台群聊权限和出站网络", code, delay);
        cancel(retry);
        retry = worker.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    void socketOpened(WebSocket opened, long attempt) {
        if (stopped) {
            opened.abort();
            return;
        }
        execute(() -> adoptSocket(opened, attempt));
    }

    private void adoptSocket(WebSocket opened, long attempt) {
        if (attempt != generation) {
            opened.abort();
            return;
        }
        socket = opened;
        opened.request(1);
    }

    void socketPacket(WebSocket opened, long attempt, String packet) {
        execute(() -> receiveAndRequest(opened, attempt, packet));
    }

    private void receiveAndRequest(WebSocket opened, long attempt, String packet) {
        if (attempt != generation || stopped) {
            return;
        }
        receive(attempt, packet);
        if (attempt == generation) {
            opened.request(1);
        }
    }

    void socketLost(long attempt, String code, boolean refresh) {
        execute(() -> disconnectIfCurrent(attempt, code, refresh));
    }

    private void disconnectIfCurrent(long attempt, String code, boolean refresh) {
        if (attempt == generation) {
            disconnect(code, refresh);
        }
    }

    @PreDestroy
    public void close() {
        stopped = true;
        state = "STOPPED";
        WebSocket active = socket;
        if (active != null) {
            active.abort();
        }
        worker.shutdownNow();
    }

    private final class SocketListener implements WebSocket.Listener {
        private static final int MAX_PACKET_BYTES = 128 * 1024;
        private static final Set<Integer> RESET_SESSION_CLOSE_CODES =
                Set.of(4004, 4006, 4007, 4009, 9001, 9005);
        private static final String EVENT_TOO_LARGE = "QQ_WS_EVENT_TOO_LARGE";
        private final long attempt;
        private final StringBuilder buffer = new StringBuilder();

        SocketListener(long attempt) {
            this.attempt = attempt;
        }

        @Override
        public void onOpen(WebSocket socket) {
            socketOpened(socket, attempt);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            if (data.length() > MAX_PACKET_BYTES - buffer.length()) {
                socketLost(attempt, EVENT_TOO_LARGE, false);
                return null;
            }
            buffer.append(data);
            if (last) {
                submitPacket(socket);
            } else {
                socket.request(1);
            }
            return null;
        }

        private void submitPacket(WebSocket socket) {
            String packet = buffer.toString();
            buffer.setLength(0);
            if (packet.getBytes(StandardCharsets.UTF_8).length > MAX_PACKET_BYTES) {
                socketLost(attempt, EVENT_TOO_LARGE, false);
                return;
            }
            socketPacket(socket, attempt, packet);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            socketLost(attempt, "QQ_WS_CLOSED_" + code, RESET_SESSION_CLOSE_CODES.contains(code));
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable failure) {
            socketLost(attempt, "QQ_WS_CONNECTION_LOST", false);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket socket, ByteBuffer message) {
            socket.request(1);
            return socket.sendPong(message);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) {
            socket.request(1);
            return null;
        }
    }
}
