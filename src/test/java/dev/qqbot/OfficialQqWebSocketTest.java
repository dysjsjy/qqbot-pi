package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import dev.qqbot.channel.*;
import dev.qqbot.message.IngestService;
import jakarta.validation.Validation;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class OfficialQqWebSocketTest {
    final ObjectMapper json = new ObjectMapper();
    HttpServer api;
    ServerSocket server;
    OfficialQqWebSocket gateway;
    AtomicInteger tokenCalls = new AtomicInteger();
    ExecutorService peer = Executors.newSingleThreadExecutor();

    @BeforeEach
    void prepare() throws Exception {
        server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        server.setSoTimeout(10000);
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext(
                "/token",
                exchange -> {
                    tokenCalls.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    reply(exchange, "{\"access_token\":\"test-access\",\"expires_in\":7200}");
                });
        api.createContext(
                "/gateway",
                exchange -> {
                    assertEquals(
                            "QQBot test-access",
                            exchange.getRequestHeaders().getFirst("Authorization"));
                    reply(
                            exchange,
                            "{\"url\":\"ws://127.0.0.1:" + server.getLocalPort() + "/gateway\"}");
                });
        api.start();
    }

    static void reply(com.sun.net.httpserver.HttpExchange exchange, String value)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    BotProperties properties() {
        String base = "http://127.0.0.1:" + api.getAddress().getPort();
        var qq =
                new BotProperties.Qq(
                        true,
                        new BotProperties.OfficialQq(
                                "app-id",
                                "test-secret",
                                base,
                                base + "/token",
                                false,
                                "websocket"));
        return new BotProperties(
                "admin",
                "",
                Path.of("target"),
                false,
                null,
                null,
                qq,
                new BotProperties.Gateway(false, "", "", "", ""),
                new BotProperties.Rag(false));
    }

    @AfterEach
    void stop() throws Exception {
        if (gateway != null) {
            gateway.close();
        }
        api.stop(0);
        server.close();
        peer.shutdownNow();
    }

    @Test
    void identifiesReceivesFragmentsHeartbeatsResumesAndReidentifiesInvalidSession()
            throws Exception {
        var ingest = mock(IngestService.class);
        when(ingest.ingest(any()))
                .thenReturn(new IngestService.Receipt(null, null, false, true, true));
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var controller = new OfficialQqEvents(properties(), ingest, validators.getValidator());
            gateway =
                    new OfficialQqWebSocket(
                            properties(),
                            json,
                            controller,
                            new OfficialQqClient(properties(), json));
            var finished = new CountDownLatch(1);
            var hold = new CountDownLatch(1);
            Future<?> scenario =
                    peer.submit(
                            () -> {
                                try {
                                    try (Socket socket = accept()) {
                                        send(
                                                socket,
                                                "{\"op\":10,\"d\":{\"heartbeat_interval\":100}}");
                                        var identify = read(socket);
                                        assertEquals(2, identify.path("op").asInt());
                                        assertEquals(
                                                "QQBot test-access",
                                                identify.path("d").path("token").asText());
                                        assertEquals(
                                                1 << 25,
                                                identify.path("d").path("intents").asInt());
                                        send(
                                                socket,
                                                "{\"op\":0,\"s\":1,\"t\":\"READY\",\"d\":{\"session_id\":\"session-one\",\"user\":{\"id\":\"bot\"}}}");
                                        var heartbeat = read(socket);
                                        assertEquals(1, heartbeat.path("op").asInt());
                                        assertEquals(1, heartbeat.path("d").asInt());
                                        send(socket, "{\"op\":11}");
                                        byte[] event =
                                                json.writeValueAsBytes(
                                                        Map.of(
                                                                "op",
                                                                0,
                                                                "s",
                                                                2,
                                                                "t",
                                                                "GROUP_AT_MESSAGE_CREATE",
                                                                "d",
                                                                Map.of(
                                                                        "id",
                                                                        "message-one",
                                                                        "group_openid",
                                                                        "group-openid",
                                                                        "author",
                                                                        Map.of(
                                                                                "member_openid",
                                                                                "member-one"),
                                                                        "content",
                                                                        "<@bot> /help",
                                                                        "timestamp",
                                                                        Instant.now().toString())));
                                        frame(socket, 1, false, Arrays.copyOfRange(event, 0, 30));
                                        frame(
                                                socket,
                                                0,
                                                true,
                                                Arrays.copyOfRange(event, 30, event.length));
                                        send(
                                                socket,
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "op",
                                                                0,
                                                                "s",
                                                                3,
                                                                "t",
                                                                "C2C_MESSAGE_CREATE",
                                                                "d",
                                                                Map.of(
                                                                        "id",
                                                                        "private-one",
                                                                        "author",
                                                                        Map.of(
                                                                                "user_openid",
                                                                                "private-user"),
                                                                        "content",
                                                                        "你好",
                                                                        "timestamp",
                                                                        Instant.now()
                                                                                .toString()))));
                                        // Wait for committed sequence in the next heartbeat before
                                        // requesting a resume.
                                        JsonNode beat;
                                        do {
                                            beat = read(socket);
                                            send(socket, "{\"op\":11}");
                                        } while (beat.path("d").asLong() != 3);
                                        send(socket, "{\"op\":7}");
                                    }
                                    try (Socket socket = accept()) {
                                        send(
                                                socket,
                                                "{\"op\":10,\"d\":{\"heartbeat_interval\":100}}");
                                        var resume = read(socket);
                                        assertEquals(6, resume.path("op").asInt());
                                        assertEquals(
                                                "session-one",
                                                resume.path("d").path("session_id").asText());
                                        assertEquals(3, resume.path("d").path("seq").asInt());
                                        send(socket, "{\"op\":9,\"d\":false}");
                                    }
                                    try (Socket socket = accept()) {
                                        send(
                                                socket,
                                                "{\"op\":10,\"d\":{\"heartbeat_interval\":100}}");
                                        assertEquals(2, read(socket).path("op").asInt());
                                        send(
                                                socket,
                                                "{\"op\":0,\"s\":3,\"t\":\"READY\",\"d\":{\"session_id\":\"session-two\"}}");
                                        read(socket);
                                        send(socket, "{\"op\":11}");
                                        finished.countDown();
                                        hold.await(4, TimeUnit.SECONDS);
                                    }
                                } catch (Exception failure) {
                                    throw new CompletionException(failure);
                                }
                            });
            gateway.start();
            assertTrue(finished.await(12, TimeUnit.SECONDS), () -> "status=" + gateway.status());
            assertEquals("ONLINE", gateway.status().state());
            assertNotNull(gateway.status().lastHeartbeatAt());
            assertTrue(tokenCalls.get() >= 2);
            verify(ingest, timeout(2000))
                    .ingest(
                            argThat(
                                    r ->
                                            r.accountId().equals("app-id")
                                                    && r.groupId().equals("group-openid")
                                                    && r.senderId().equals("member-one")
                                                    && r.text().equals("/help")
                                                    && r.mentionsBot()));
            verify(ingest, timeout(2000))
                    .ingest(
                            argThat(
                                    request ->
                                            request.privateChat()
                                                    && request.groupId().equals("private-user")
                                                    && request.text().equals("你好")));
            assertFalse(gateway.status().toString().contains("test-access"));
            assertEquals(2, controller.events().size());
            gateway.close();
            hold.countDown();
            scenario.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void missingHeartbeatAckReconnectsAndTokenErrorsExposeOnlySafeCodes() throws Exception {
        var controller = mock(OfficialQqEvents.class);
        gateway =
                new OfficialQqWebSocket(
                        properties(), json, controller, new OfficialQqClient(properties(), json));
        Future<?> scenario =
                peer.submit(
                        () -> {
                            try (Socket socket = accept()) {
                                send(socket, "{\"op\":10,\"d\":{\"heartbeat_interval\":100}}");
                                read(socket);
                                send(
                                        socket,
                                        "{\"op\":0,\"s\":1,\"t\":\"READY\",\"d\":{\"session_id\":\"session-one\"}}");
                                assertEquals(1, read(socket).path("op").asInt());
                                assertEquals(-1, socket.getInputStream().read());
                            } catch (Exception failure) {
                                throw new CompletionException(failure);
                            }
                        });
        gateway.start();
        scenario.get(8, TimeUnit.SECONDS);
        assertEquals("QQ_WS_HEARTBEAT_TIMEOUT", gateway.status().error());
        gateway.close();
        api.removeContext("/token");
        api.createContext(
                "/token",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    reply(exchange, "{\"code\":100016,\"message\":\"sensitive-body-test-secret\"}");
                });
        gateway =
                new OfficialQqWebSocket(
                        properties(), json, controller, new OfficialQqClient(properties(), json));
        gateway.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!"QQ_TOKEN_100016".equals(gateway.status().error())
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("QQ_TOKEN_100016", gateway.status().error());
        assertFalse(gateway.status().toString().contains("sensitive-body"));
        verify(controller).identifyBot(any());
        verifyNoMoreInteractions(controller);
    }

    Socket accept() throws Exception {
        Socket socket = server.accept();
        socket.setSoTimeout(6000);
        InputStream in = socket.getInputStream();
        var header = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            header.write(b);
            if (header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                break;
            }
        }
        String key =
                header.toString(StandardCharsets.US_ASCII)
                        .lines()
                        .filter(line -> line.toLowerCase().startsWith("sec-websocket-key:"))
                        .findFirst()
                        .orElseThrow()
                        .split(":", 2)[1]
                        .trim();
        String accept =
                Base64.getEncoder()
                        .encodeToString(
                                MessageDigest.getInstance("SHA-1")
                                        .digest(
                                                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                                        .getBytes(StandardCharsets.US_ASCII)));
        socket.getOutputStream()
                .write(
                        ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                                        + accept
                                        + "\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    void send(Socket socket, String text) throws Exception {
        frame(socket, 1, true, text.getBytes(StandardCharsets.UTF_8));
    }

    void frame(Socket socket, int op, boolean last, byte[] bytes) throws Exception {
        var out = new DataOutputStream(socket.getOutputStream());
        out.writeByte((last ? 128 : 0) | op);
        if (bytes.length < 126) {
            out.writeByte(bytes.length);
        } else {
            out.writeByte(126);
            out.writeShort(bytes.length);
        }
        out.write(bytes);
        out.flush();
    }

    JsonNode read(Socket socket) throws Exception {
        var in = new DataInputStream(socket.getInputStream());
        assertEquals(129, in.readUnsignedByte());
        int size = in.readUnsignedByte();
        assertTrue((size & 128) != 0);
        size &= 127;
        if (size == 126) {
            size = in.readUnsignedShort();
        }
        byte[] mask = in.readNBytes(4), payload = in.readNBytes(size);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i % 4];
        }
        return json.readTree(payload);
    }
}
