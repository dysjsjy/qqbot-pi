package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import dev.qqbot.channel.*;
import dev.qqbot.message.AgentWorker;
import dev.qqbot.model.GenerationObserver;
import dev.qqbot.model.GenerationRequest;
import dev.qqbot.persistence.Db;
import dev.qqbot.pi.PiRuntime;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "bot.scheduling=false",
            "bot.rag.enabled=false",
            "bot.qq.enabled=true",
            "bot.qq.official.transport=webhook",
            "bot.qq.official.app-id=official-app",
            "bot.qq.official.bot-openid=bot-openid",
            "bot.qq.official.app-secret=test-secret",
            "bot.pi.provider=fixture",
            "bot.pi.model=fixture",
            "bot.admin-password-hash=$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234"
        })
@ActiveProfiles("sqlite")
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OfficialQqApplicationIntegrationTest {
    @TempDir static Path directory;
    static HttpServer server;
    static final List<JsonNode> sent = new CopyOnWriteArrayList<>();
    static final List<String> privateTargets = new CopyOnWriteArrayList<>();
    static final ObjectMapper json = new ObjectMapper();
    @Autowired MockMvc mvc;
    @Autowired Db db;
    @Autowired AgentWorker agents;
    @Autowired OutboxWorker outbox;
    @MockitoBean PiRuntime pi;
    final OfficialQqSignature signatures = new OfficialQqSignature("test-secret");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/token",
                e -> {
                    e.getRequestBody().readAllBytes();
                    byte[] reply =
                            "{\"access_token\":\"token\",\"expires_in\":7200}"
                                    .getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(200, reply.length);
                    e.getResponseBody().write(reply);
                    e.close();
                });
        server.createContext(
                "/v2/groups/group-openid/messages",
                e -> {
                    assertEquals("QQBot token", e.getRequestHeaders().getFirst("Authorization"));
                    sent.add(json.readTree(e.getRequestBody()));
                    byte[] reply =
                            ("{\"id\":\"receipt-" + sent.size() + "\"}")
                                    .getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(200, reply.length);
                    e.getResponseBody().write(reply);
                    e.close();
                });
        server.start();
        server.createContext(
                "/v2/users/",
                e -> {
                    assertEquals("QQBot token", e.getRequestHeaders().getFirst("Authorization"));
                    privateTargets.add(e.getRequestURI().getPath());
                    sent.add(json.readTree(e.getRequestBody()));
                    byte[] reply =
                            ("{\"id\":\"private-receipt-" + sent.size() + "\"}")
                                    .getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(200, reply.length);
                    e.getResponseBody().write(reply);
                    e.close();
                });
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        registry.add(
                "spring.datasource.url", () -> "jdbc:sqlite:" + directory.resolve("official.db"));
        registry.add("bot.qq.official.api-base-url", () -> base);
        registry.add("bot.qq.official.token-url", () -> base + "/token");
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void clear() {
        for (String table :
                List.of(
                        "outbox",
                        "daily_run",
                        "daily_schedule",
                        "agent_run",
                        "conversation",
                        "memory_document",
                        "memory_policy",
                        "memory_chunk",
                        "group_message",
                        "collection_gap",
                        "reply_policy",
                        "group_config")) {
            db.exec("delete from " + table);
        }
        sent.clear();
        privateTargets.clear();
    }

    void receive(String type, String id, String text) throws Exception {
        String body =
                json.writeValueAsString(
                        Map.of(
                                "op",
                                0,
                                "t",
                                type,
                                "d",
                                Map.of(
                                        "id",
                                        id,
                                        "group_openid",
                                        "group-openid",
                                        "author",
                                        Map.of("member_openid", "member-openid", "username", "成员"),
                                        "content",
                                        text,
                                        "timestamp",
                                        Instant.now().toString())));
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        mvc.perform(
                        post("/callbacks/qq/official")
                                .header("X-Bot-Appid", "official-app")
                                .header("X-Signature-Timestamp", timestamp)
                                .header(
                                        "X-Signature-Ed25519",
                                        signatures.sign(
                                                timestamp, body.getBytes(StandardCharsets.UTF_8)))
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.op").value(12));
    }

    long count(String table) {
        return ((Number) db.one("select count(*) n from " + table).get("n")).longValue();
    }

    @Autowired BotProperties properties;

    void privateMessage(String user, String id, String text) throws Exception {
        String body =
                json.writeValueAsString(
                        Map.of(
                                "op",
                                0,
                                "t",
                                "C2C_MESSAGE_CREATE",
                                "d",
                                Map.of(
                                        "id",
                                        id,
                                        "author",
                                        Map.of("user_openid", user),
                                        "content",
                                        text,
                                        "timestamp",
                                        Instant.now().toString())));
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        mvc.perform(
                        post("/callbacks/qq/official")
                                .header("X-Bot-Appid", "official-app")
                                .header("X-Signature-Timestamp", timestamp)
                                .header(
                                        "X-Signature-Ed25519",
                                        signatures.sign(
                                                timestamp, body.getBytes(StandardCharsets.UTF_8)))
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.op").value(12));
    }

    @Test
    void privateChatRepliesImmediatelyAndIsolatesUsersAndGroupHistory() throws Exception {
        privateMessage("user-one", "help", "/help");
        privateMessage("user-one", "help", "/help");
        var first = db.one("select * from group_config where target_id='user-one'");
        assertEquals("PRIVATE", first.get("chat_type"));
        assertEquals(true, first.get("enabled"));
        assertEquals(1, count("group_message"));
        assertEquals(1, count("outbox"));
        outbox.tick();
        assertEquals(List.of("/v2/users/user-one/messages"), privateTargets);
        assertEquals("help", sent.get(0).path("msg_id").asText());
        assertEquals(1, sent.get(0).path("msg_seq").asInt());
        assertTrue(sent.get(0).path("content").asText().contains("私聊命令"));
        privateMessage("user-two", "same-message-key", "第二个用户的秘密");
        UUID other =
                (UUID) db.one("select id from group_config where target_id='user-two'").get("id");
        db.exec("update agent_run set state='CANCELLED' where group_id=?", other);
        new dev.qqbot.memory.MarkdownMemory(db, properties).write(other, "第二用户记忆秘密");
        UUID groupId = UUID.randomUUID();
        db.exec(
                "insert into group_config(id,channel,account_id,external_id,name,enabled) values(?,'QQ','official-app','user-one','群',true)",
                groupId);
        new dev.qqbot.memory.MarkdownMemory(db, properties).write(groupId, "群记忆秘密");
        privateMessage("user-one", "same-message-key", "你好");
        privateMessage("user-one", "same-message-key", "你好");
        when(pi.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenAnswer(
                        call -> {
                            GenerationRequest request = call.getArgument(0);
                            GenerationObserver observer = call.getArgument(1);
                            observer.dispatched();
                            String prompt = request.userPrompt();
                            assertTrue(prompt.endsWith("你好"));
                            assertFalse(prompt.contains("第二个用户的秘密"));
                            assertFalse(prompt.contains("第二用户记忆秘密"));
                            assertFalse(prompt.contains("群记忆秘密"));
                            return new PiRuntime.Result("私聊模型回答", null);
                        });
        agents.tick();
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!"SUCCEEDED"
                .equals(
                        db.one("select state from agent_run where group_id=?", first.get("id"))
                                .get("state"))) {
            if (System.nanoTime() > until) {
                fail("private chat timed out");
            }
            Thread.sleep(20);
        }
        outbox.tick();
        assertEquals("/v2/users/user-one/messages", privateTargets.get(1));
        assertEquals("私聊模型回答", sent.get(1).path("content").asText());
        assertEquals("same-message-key", sent.get(1).path("msg_id").asText());
        assertEquals(
                1L,
                ((Number)
                                db.one(
                                                "select count(*) n from model_call where group_id=? and dispatched=true and purpose='CHAT'",
                                                first.get("id"))
                                        .get("n"))
                        .longValue());
        privateMessage("user-one", "summary", "/summary yesterday");
        outbox.tick();
        assertEquals(0, count("daily_run"));
        assertTrue(sent.get(2).path("content").asText().contains("私聊不支持"));
        mvc.perform(get("/api/admin/qq/official/events"))
                .andExpect(jsonPath("$[0].chatType").value("PRIVATE"));
        String config =
                json.writeValueAsString(
                        Map.of(
                                "channel",
                                "QQ",
                                "accountId",
                                "official-app",
                                "externalId",
                                first.get("external_id"),
                                "name",
                                "已暂停私聊",
                                "enabled",
                                false,
                                "replyEnabled",
                                true,
                                "adminIds",
                                "",
                                "revision",
                                0));
        mvc.perform(
                        patch("/api/admin/groups/" + first.get("id"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(config))
                .andExpect(status().isOk());
        long before = count("group_message");
        privateMessage("user-one", "paused", "不应回复");
        assertEquals(before, count("group_message"));
        mvc.perform(
                        post("/api/admin/groups/" + first.get("id") + "/summary/yesterday")
                                .with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void discoverEnableDeduplicateAndDeliverCommandsAndModelReplies() throws Exception {
        mvc.perform(get("/api/admin/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateways.QQ.mode").doesNotExist())
                .andExpect(jsonPath("$.gateways.QQ.accountId").value("official-app"));
        receive("GROUP_AT_MESSAGE_CREATE", "before-enable", "test");
        var group = db.one("select * from group_config where external_id='group-openid'");
        assertNotNull(group);
        assertEquals("official-app", group.get("account_id"));
        assertEquals(false, group.get("enabled"));
        assertEquals(0, count("group_message"));
        mvc.perform(get("/api/admin/qq/official/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].memberOpenid").value("member-openid"))
                .andExpect(jsonPath("$[0].accepted").value(false));
        String config =
                "{\"channel\":\"QQ\",\"accountId\":\"official-app\",\"externalId\":\"group-openid\",\"name\":\"官方群\",\"enabled\":true,\"replyEnabled\":true,\"adminIds\":\"member-openid\",\"revision\":0}";
        mvc.perform(
                        patch("/api/admin/groups/" + group.get("id"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(config))
                .andExpect(status().isOk());
        receive("GROUP_AT_MESSAGE_CREATE", "help", " /help");
        receive("GROUP_AT_MESSAGE_CREATE", "help", " /help");
        assertEquals(1, count("group_message"));
        assertEquals(1, count("outbox"));
        assertEquals(0, count("agent_run"));
        verifyNoInteractions(pi);
        outbox.tick();
        assertEquals(1, sent.size());
        assertEquals("help", sent.get(0).path("msg_id").asText());
        assertEquals(1, sent.get(0).path("msg_seq").asInt());
        assertEquals("SENT", db.one("select state from outbox").get("state"));

        // Full-message mode must reply without waiting for an AT event that may never arrive.
        receive("GROUP_MESSAGE_CREATE", "question", "<@bot-openid> 你好");
        assertEquals(1, count("agent_run"));
        receive("GROUP_AT_MESSAGE_CREATE", "question", " 你好");
        receive("GROUP_AT_MESSAGE_CREATE", "question", " 你好");
        assertEquals(2, count("group_message"));
        assertEquals(1, count("agent_run"));
        when(pi.generate(
                        argThat((GenerationRequest request) -> request.userPrompt().contains("你好")),
                        any(GenerationObserver.class)))
                .thenAnswer(
                        call -> {
                            GenerationObserver observer = call.getArgument(1);
                            observer.dispatched();
                            return new PiRuntime.Result("模型回答", null);
                        });
        agents.tick();
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!"SUCCEEDED".equals(db.one("select state from agent_run").get("state"))) {
            if (System.nanoTime() > until) {
                fail("model run timed out");
            }
            Thread.sleep(20);
        }
        outbox.tick();
        assertEquals(2, sent.size());
        assertEquals("question", sent.get(1).path("msg_id").asText());
        assertEquals("模型回答", sent.get(1).path("content").asText());
        assertEquals(
                1L,
                ((Number)
                                db.one(
                                                "select count(*) n from model_call where group_id=? and dispatched=true and purpose='CHAT'",
                                                group.get("id"))
                                        .get("n"))
                        .longValue());

        // Preserved unrelated account data must not be sent using the official application's token.
        UUID old = UUID.randomUUID(), delivery = UUID.randomUUID();
        db.exec(
                "insert into group_config(id,channel,account_id,external_id,name,enabled) values(?,'QQ','old-qq-account','12345','旧账号',true)",
                old);
        db.exec(
                "insert into outbox(id,group_id,result_key,epoch,part_index,body) values(?,?,'old-result',0,0,'旧回复')",
                delivery,
                old);
        outbox.tick();
        assertEquals(
                "CANCELLED", db.one("select state from outbox where id=?", delivery).get("state"));
        assertEquals(2, sent.size());
    }
}
