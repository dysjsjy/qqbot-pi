package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import dev.qqbot.channel.*;
import dev.qqbot.delivery.MessageTransport.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class OfficialQqTest {
    HttpServer server;
    String base;
    ObjectMapper json = new ObjectMapper();
    AtomicInteger tokenCalls = new AtomicInteger(), sends = new AtomicInteger();
    AtomicReference<JsonNode> payload = new AtomicReference<>();
    AtomicReference<String> result = new AtomicReference<>("{\"id\":\"official-receipt\"}");
    AtomicInteger responseCode = new AtomicInteger(200);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext(
                "/token",
                e -> {
                    tokenCalls.incrementAndGet();
                    var body = json.readTree(e.getRequestBody());
                    assertEquals("app-id", body.path("appId").asText());
                    assertEquals("test-secret", body.path("clientSecret").asText());
                    byte[] reply =
                            "{\"access_token\":\"access-value\",\"expires_in\":\"7200\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(200, reply.length);
                    e.getResponseBody().write(reply);
                    e.close();
                });
        server.createContext(
                "/v2/groups/group-openid/messages",
                e -> {
                    assertEquals(
                            "QQBot access-value", e.getRequestHeaders().getFirst("Authorization"));
                    assertEquals("app-id", e.getRequestHeaders().getFirst("X-Union-Appid"));
                    sends.incrementAndGet();
                    payload.set(json.readTree(e.getRequestBody()));
                    byte[] reply = result.get().getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(responseCode.get(), reply.length);
                    e.getResponseBody().write(reply);
                    e.close();
                });
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    BotProperties properties(boolean active) {
        var g =
                new BotProperties.Qq(
                        true,
                        new BotProperties.OfficialQq(
                                "app-id", "test-secret", base, base + "/token", active));
        return new BotProperties(
                "admin",
                "",
                Path.of("target"),
                false,
                null,
                null,
                g,
                new BotProperties.Gateway(false, "", "", "", ""),
                new BotProperties.Rag(false));
    }

    Message message(String text, Reply reply) {
        return new Message("QQ", "group-openid", false, text, "id", reply);
    }

    @Test
    void ed25519MatchesIndependentVectorAndRejectsTampering() {
        var signatures = new OfficialQqSignature("naOC0ocQE3shWLAfffVLB1rhYPG7naOC");
        byte[] body =
                "{ \"op\": 0,\"d\": {}, \"t\": \"GATEWAY_EVENT_NAME\"}"
                        .getBytes(StandardCharsets.UTF_8);
        // QQ's published seed; expected signature independently calculated with Python
        // cryptography.
        String expected =
                "2eb9983ebb8bb209e78fd095942f58e442656656e7975d01e64f9023a84b7c964290fdd40e5500c33867ccfe9563b7e0b6bac0e1d42c13e787b304fd51f71102";
        assertEquals(expected, signatures.sign("1725442341", body));
        assertTrue(signatures.verify("1725442341", body, expected));
        assertFalse(signatures.verify("1725442342", body, expected));
        assertFalse(
                signatures.verify("1725442341", "{}".getBytes(StandardCharsets.UTF_8), expected));
        assertFalse(signatures.verify(null, body, expected));
        assertFalse(signatures.verify("1725442341", body, "bad-hex"));
    }

    @Test
    void passiveRepliesPreserveSourceAndSequenceAndReuseToken() {
        var sender = TestServices.sender(properties(false), json);
        for (int seq = 1; seq <= 3; seq++) {
            var delivery =
                    sender.send(message("中文回复", new Reply("original-message", Instant.now(), seq)));
            assertEquals(State.ACCEPTED, delivery.state());
            assertEquals("official-receipt", delivery.platformId());
            assertEquals("original-message", payload.get().path("msg_id").asText());
            assertEquals(seq, payload.get().path("msg_seq").asInt());
            assertEquals(0, payload.get().path("msg_type").asInt());
            assertEquals("中文回复", payload.get().path("content").asText());
        }
        assertEquals(1, tokenCalls.get());
        assertEquals(3, sends.get());
    }

    @Test
    void activeMessagesRequireExplicitOptInAndExpiredRepliesNeverBecomeActive() {
        var sender = TestServices.sender(properties(false), json);
        assertEquals("QQ_ACTIVE_MESSAGES_DISABLED", sender.send(message("text", null)).code());
        assertEquals(
                "QQ_REPLY_EXPIRED",
                sender.send(
                                message(
                                        "text",
                                        new Reply("source", Instant.now().minusSeconds(301), 1)))
                        .code());
        assertEquals(
                "QQ_REPLY_LIMIT",
                sender.send(message("text", new Reply("source", Instant.now(), 6))).code());
        assertEquals(0, tokenCalls.get());
        assertEquals(0, sends.get());
        assertEquals(
                State.ACCEPTED,
                TestServices.sender(properties(true), json).send(message("daily", null)).state());
        assertFalse(payload.get().has("msg_id"));
    }

    @Test
    void failuresAndMissingReceiptsAreNotReportedAsSent() {
        var sender = TestServices.sender(properties(false), json);
        var reply = new Reply("source", Instant.now(), 1);
        result.set("{\"code\":40034005,\"message\":\"expired\"}");
        assertEquals("QQ_API_40034005", sender.send(message("text", reply)).code());
        result.set("{}");
        assertEquals(State.UNKNOWN, sender.send(message("text", reply)).state());
        responseCode.set(429);
        assertEquals(State.RETRYABLE, sender.send(message("text", reply)).state());
        responseCode.set(500);
        assertEquals(State.UNKNOWN, sender.send(message("text", reply)).state());
        responseCode.set(200);
        result.set("{\"code\":40054005}");
        assertEquals(State.UNKNOWN, sender.send(message("text", reply)).state());
    }

    @Test
    void unauthorizedMessageRefreshesTokenOnce() {
        server.removeContext("/v2/groups/group-openid/messages");
        server.createContext(
                "/v2/groups/group-openid/messages",
                e -> {
                    e.getRequestBody().readAllBytes();
                    int n = sends.incrementAndGet();
                    byte[] data =
                            (n == 1 ? "{}" : "{\"id\":\"receipt\"}")
                                    .getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(n == 1 ? 401 : 200, data.length);
                    e.getResponseBody().write(data);
                    e.close();
                });
        var result =
                TestServices.sender(properties(false), json)
                        .send(message("text", new Reply("source", Instant.now(), 1)));
        assertEquals(State.ACCEPTED, result.state());
        assertEquals(2, tokenCalls.get());
        assertEquals(2, sends.get());
    }

    @Test
    void tokenBusinessErrorAtHttp200PreventsMessageSend() {
        server.removeContext("/token");
        server.createContext(
                "/token",
                e -> {
                    e.getRequestBody().readAllBytes();
                    byte[] data =
                            "{\"code\":100016,\"message\":\"invalid credentials\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(200, data.length);
                    e.getResponseBody().write(data);
                    e.close();
                });
        var delivery =
                TestServices.sender(properties(false), json)
                        .send(message("text", new Reply("source", Instant.now(), 1)));
        assertEquals(State.FAILED, delivery.state());
        assertEquals("QQ_TOKEN_100016", delivery.code());
        assertEquals(0, sends.get());
    }
}
