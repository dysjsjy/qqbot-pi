/**
 * [INPUT]: 使用合成消息、回复夹具、临时 SQLite 和模型桩。
 * [OUTPUT]: 验证上下文、模型计量和发件箱内容。
 * [POS]: 聊天与查证业务的端到端测试夹具。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.memory.*;
import dev.qqbot.message.AgentWorker;
import dev.qqbot.model.*;
import dev.qqbot.usage.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class T10FixtureTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ModelUsage USAGE =
            new ModelUsage(100L, 10L, 20L, 0L, 5L, UsageStatus.REPORTED);
    private static final MemoryProperties BUDGETS =
            new MemoryProperties(
                    8000,
                    800,
                    32768,
                    1024,
                    1000,
                    "USD",
                    BigDecimal.ONE,
                    new BigDecimal("2"),
                    new BigDecimal("0.1"),
                    new BigDecimal("1.25"),
                    "fixture-v1");

    MemoryV2TestDatabase database(Path file) {
        return new MemoryV2TestDatabase(file);
    }

    @TestFactory
    Stream<DynamicTest> L07FixturesUseRealHistoryAssemblyMeteringWorkerAndOutbox() {
        return MemoryV2FixtureLoader.cases().stream()
                .map(
                        specification ->
                                DynamicTest.dynamicTest(
                                        specification.path("id").asText(),
                                        () -> exercise(specification)));
    }

    private void exercise(JsonNode specification) throws Exception {
        String id = specification.path("id").asText();
        try (var fixture = database(directory.resolve(id + "-first.db"));
                var repeated = database(directory.resolve(id + "-repeat.db"))) {
            BotProperties properties = properties(directory.resolve(id + "-first"));
            var files = new MarkdownMemory(fixture.db, properties);
            var loaded = new MemoryV2FixtureLoader(fixture.db, specification).load(files);
            var repeat =
                    new MemoryV2FixtureLoader(repeated.db, specification)
                            .load(
                                    new MarkdownMemory(
                                            repeated.db,
                                            properties(directory.resolve(id + "-repeat"))));
            assertEquals(loaded.entities(), repeat.entities());
            assertEquals(loaded.sourceKeys(), repeat.sourceKeys());
            assertEquals(loaded.request(), repeat.request());
            for (String table :
                    List.of(
                            "group_config",
                            "group_message",
                            "conversation",
                            "agent_run",
                            "outbox")) {
                assertEquals(
                        snapshot(fixture, table),
                        snapshot(repeated, table),
                        id + " reproducible " + table);
            }
            var request = loaded.request();
            var original =
                    fixture.db.one(
                            "select context_seq,context_occurred_at from agent_run where id=?",
                            loaded.runId());
            var history = new ConversationHistory(fixture.db).load(request);
            var selectedKeys =
                    new HashSet<>(history.stream().map(HistoryEvent::sourceKey).toList());
            JsonNode expected = specification.path("expected");
            for (JsonNode alias : expected.path("include")) {
                assertTrue(
                        selectedKeys.contains(loaded.sourceKeys().get(alias.asText())),
                        id + " history include " + alias);
            }
            for (JsonNode alias : expected.path("exclude")) {
                assertFalse(
                        selectedKeys.contains(loaded.sourceKeys().get(alias.asText())),
                        id + " history exclude " + alias);
            }
            for (JsonNode alias : expected.path("explicitlyReferenced")) {
                assertTrue(
                        history.stream()
                                .anyMatch(
                                        event ->
                                                event.sourceKey()
                                                                .equals(
                                                                        loaded.sourceKeys()
                                                                                .get(
                                                                                        alias
                                                                                                .asText()))
                                                        && event.explicitlyReferenced()));
            }
            var embedding = mock(EmbeddingClient.class);
            var memory = memory(fixture, files, properties, loaded, embedding);
            var bundle = memory.assemble(request);
            var generated = new CopyOnWriteArrayList<GenerationRequest>();
            var delegate = mock(TextGenerator.class);
            when(delegate.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                    .thenAnswer(
                            invocation -> {
                                GenerationRequest generation = invocation.getArgument(0);
                                GenerationObserver observer = invocation.getArgument(1);
                                generated.add(generation);
                                observer.dispatched();
                                observer.usage(USAGE);
                                return new TextGenerator.Result("合成正常答复", null, USAGE);
                            });
            var metered =
                    new MeteredTextGenerator(
                            delegate,
                            new ModelCallStore(fixture.db, properties, BUDGETS, loaded.clock()));
            var worker =
                    new AgentWorker(
                            fixture.db,
                            metered,
                            memory,
                            new AgentWorker.Settings(properties, BUDGETS, loaded.clock()));
            try {
                worker.tick();
                awaitFinished(fixture, loaded.runId());
                int calls = expected.path("generationCalls").asInt();
                assertEquals(calls, generated.size(), id + " dispatched");
                assertEquals(
                        calls,
                        fixture.db
                                .list("select id from model_call where run_id=?", loaded.runId())
                                .size());
                assertEquals(
                        calls,
                        fixture.db
                                .list(
                                        "select id from model_call where run_id=? and dispatched=true and state='SUCCEEDED' and usage_status='REPORTED'",
                                        loaded.runId())
                                .size());
                verify(delegate, times(calls))
                        .generate(any(GenerationRequest.class), any(GenerationObserver.class));
                String reply =
                        String.join(
                                "",
                                fixture
                                        .db
                                        .list(
                                                "select body from outbox where agent_run_id=? order by part_index",
                                                loaded.runId())
                                        .stream()
                                        .map(row -> (String) row.get("body"))
                                        .toList());
                assertEquals(calls == 0 ? expected.path("localReply").asText() : "合成正常答复", reply);
                if (calls > 0) {
                    GenerationRequest actual = generated.get(0);
                    assertEquals(bundle.systemPrompt(), actual.systemPrompt());
                    assertEquals(bundle.userPrompt(), actual.userPrompt());
                    assertEquals(request.scope().groupId(), actual.groupId());
                    assertEquals(request.scope().epoch(), actual.epoch());
                    assertEquals(loaded.runId(), actual.runId());
                    assertEquals(Purpose.CHAT, actual.purpose());
                    assertEquals(
                            bundle.stats().estimatedInputTokens(),
                            actual.systemPrompt().getBytes(StandardCharsets.UTF_8).length
                                    + actual.userPrompt().getBytes(StandardCharsets.UTF_8).length
                                    + 256);
                    assertEquals(
                            expected.path("currentQuestionOccurrences").asInt(),
                            occurrences(actual.userPrompt(), request.question()));
                    for (JsonNode alias : expected.path("include")) {
                        String key = loaded.sourceKeys().get(alias.asText());
                        assertTrue(
                                bundle.stats().selectedKeys().contains(key),
                                id + " selected " + alias);
                        assertTrue(
                                actual.userPrompt()
                                        .contains(
                                                loaded.events()
                                                        .get(alias.asText())
                                                        .path("body")
                                                        .asText()),
                                id + " prompt include " + alias);
                    }
                    for (JsonNode alias : expected.path("exclude")) {
                        assertFalse(
                                bundle.stats()
                                        .selectedKeys()
                                        .contains(loaded.sourceKeys().get(alias.asText())));
                        assertFalse(
                                actual.userPrompt()
                                        .contains(
                                                loaded.events()
                                                        .get(alias.asText())
                                                        .path("body")
                                                        .asText()),
                                id + " prompt exclude " + alias);
                    }
                    assertMemberLabels(actual.userPrompt(), expected, loaded);
                } else {
                    assertTrue(generated.isEmpty());
                    assertTrue(fixture.db.list("select id from model_call").isEmpty());
                }
                assertEquals(
                        original,
                        fixture.db.one(
                                "select context_seq,context_occurred_at from agent_run where id=?",
                                loaded.runId()));
                if (id.equals("FX03")) {
                    assertPartialDeliveryFixture(fixture, loaded);
                }
                if (id.equals("FX04")) {
                    assertEquals(
                            11L,
                            ((Number)
                                            fixture.db
                                                    .one(
                                                            "select event_seq from group_config where id=?",
                                                            request.scope().groupId())
                                                    .get("event_seq"))
                                    .longValue());
                }
                if (id.equals("FX06")) {
                    assertEquals(
                            "PRIVATE",
                            fixture.db
                                    .one(
                                            "select chat_type from group_config where id=?",
                                            request.scope().groupId())
                                    .get("chat_type"));
                }
                verifyNoInteractions(embedding);
            } finally {
                worker.close();
            }
        }
    }

    private static BotProperties properties(Path state) {
        var gateway = new BotProperties.Gateway(false, "", "", "fixture", "");
        return new BotProperties(
                "admin",
                "",
                state,
                false,
                new BotProperties.Pi("node", "unused", "local", "fixture", "KEY", "", 5, ""),
                null,
                new BotProperties.Qq(
                        gateway.enabled(),
                        new BotProperties.OfficialQq(
                                gateway.accountId(),
                                "",
                                "https://api.bot.qq.com",
                                "https://api.bot.qq.com/app/getAppAccessToken",
                                false)),
                gateway,
                new BotProperties.Rag(false));
    }

    private static MemoryService memory(
            MemoryV2TestDatabase fixture,
            MarkdownMemory files,
            BotProperties properties,
            MemoryV2FixtureLoader.LoadedCase loaded,
            EmbeddingClient embedding) {
        var index = new MemorySearchIndex(fixture.db, files, loaded.clock());
        var vectors = new VectorMemory(fixture.db, embedding, properties, loaded.clock());
        var context =
                new MemoryContext(
                        fixture.db,
                        files,
                        new ConversationHistory(fixture.db),
                        new ContextAssembler(BUDGETS, JSON),
                        BUDGETS,
                        loaded.clock());
        return new MemoryService(
                new MarkdownSearch(fixture.db, index, loaded.clock()),
                context,
                vectors,
                properties,
                new EvidenceSearch(index, vectors, properties));
    }

    private static List<Map<String, Object>> snapshot(MemoryV2TestDatabase fixture, String table) {
        String columns =
                switch (table) {
                    case "group_config" -> "id,epoch,event_seq,chat_type,target_id";
                    case "group_message" ->
                            "id,group_id,message_key,sender_id,sender_name,body,occurred_at,received_at,epoch,event_seq,reply_to_key";
                    case "conversation" -> "id,group_id,sender_id,generation";
                    case "agent_run" ->
                            "id,group_id,message_id,conversation_id,generation,epoch,state,prompt,final_text,context_seq,context_occurred_at";
                    case "outbox" ->
                            "id,group_id,result_key,agent_run_id,conversation_id,generation,epoch,part_index,body,state,event_seq,delivered_at";
                    default -> throw new IllegalArgumentException("Unknown fixture table");
                };
        return fixture.db.list("select " + columns + " from " + table + " order by id");
    }

    private static void awaitFinished(MemoryV2TestDatabase fixture, UUID run) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (; ; ) {
            var state = fixture.db.one("select state from agent_run where id=?", run);
            if (!Set.of("QUEUED", "RUNNING").contains(state.get("state"))) {
                assertEquals("SUCCEEDED", state.get("state"));
                return;
            }
            if (System.nanoTime() > deadline) {
                fail("Fixture worker did not finish: " + run);
            }
            Thread.sleep(10);
        }
    }

    private static void assertMemberLabels(
            String prompt, JsonNode expected, MemoryV2FixtureLoader.LoadedCase loaded)
            throws Exception {
        var byBody = new HashMap<String, String>();
        for (String line : prompt.lines().filter(text -> text.startsWith("{")).toList()) {
            JsonNode row = JSON.readTree(line);
            byBody.put(row.path("text").asText(), row.path("member").asText());
        }
        for (String rule : List.of("sameMemberLabel", "differentMemberLabel")) {
            for (JsonNode pair : expected.path(rule)) {
                String
                        first =
                                byBody.get(
                                        loaded.events()
                                                .get(pair.get(0).asText())
                                                .path("body")
                                                .asText()),
                        second =
                                byBody.get(
                                        loaded.events()
                                                .get(pair.get(1).asText())
                                                .path("body")
                                                .asText());
                assertNotNull(first);
                assertNotNull(second);
                if (rule.equals("sameMemberLabel")) {
                    assertEquals(first, second);
                } else {
                    assertNotEquals(first, second);
                }
            }
        }
    }

    private static void assertPartialDeliveryFixture(
            MemoryV2TestDatabase fixture, MemoryV2FixtureLoader.LoadedCase loaded) {
        UUID run = loaded.entities().get("run:G1:a1");
        assertEquals(
                "SUCCEEDED",
                fixture.db.one("select state from agent_run where id=?", run).get("state"));
        assertEquals(4, fixture.db.list("select id from outbox where agent_run_id=?", run).size());
        String complete =
                (String)
                        fixture.db
                                .one("select final_text from agent_run where id=?", run)
                                .get("final_text");
        for (String alias : List.of("bot0", "bot1", "bot2", "bot3")) {
            assertTrue(complete.contains(loaded.events().get(alias).path("body").asText()));
        }
        for (var row :
                fixture.db.list(
                        "select state,event_seq,delivered_at from outbox where agent_run_id=?",
                        run)) {
            if (!"SENT".equals(row.get("state"))) {
                assertNull(row.get("event_seq"));
                assertNull(row.get("delivered_at"));
            }
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0, offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
