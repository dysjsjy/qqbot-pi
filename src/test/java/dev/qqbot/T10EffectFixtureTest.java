/**
 * [INPUT]: 使用固定聊天与查证测试夹具、临时 SQLite 和模型桩。
 * [OUTPUT]: 验证证据选择、回复内容与模型分发次数。
 * [POS]: 记忆预算和回复路径的效果回归。
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

class T10EffectFixtureTest {
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
                    "effect-structure-v1");

    MemoryV2TestDatabase database(Path file) {
        return new MemoryV2TestDatabase(file);
    }

    @Test
    void E01FrozenDatasetContainsAllCategoriesAndIndependentScoringFields() {
        var cases = MemoryV2FixtureLoader.effectCases();
        assertEquals(40, cases.size());
        assertEquals(40, cases.stream().map(item -> item.path("id").asText()).distinct().count());
        var expected =
                Map.of(
                        "continuity",
                        8L,
                        "member",
                        8L,
                        "history",
                        8L,
                        "correction",
                        6L,
                        "unknown",
                        6L,
                        "long",
                        4L);
        for (var entry : expected.entrySet()) {
            assertEquals(
                    entry.getValue(),
                    cases.stream()
                            .filter(item -> entry.getKey().equals(item.path("category").asText()))
                            .count(),
                    entry.getKey());
        }
        assertEquals(
                List.of("EQ01", "EQ02", "EQ03", "EQ04", "EQ37", "EQ38", "EQ39", "EQ40"),
                cases.stream()
                        .filter(item -> item.path("redundantLongContext").asBoolean())
                        .map(item -> item.path("id").asText())
                        .toList());
        for (JsonNode item : cases) {
            for (String field : List.of("expectedFacts", "forbiddenClaims", "expectedMember")) {
                assertTrue(item.path(field).isArray(), item.path("id") + " " + field);
            }
            assertFalse(item.path("trigger").path("question").asText().isBlank());
            if (item.path("category").asText().equals("unknown")) {
                assertTrue(item.path("expectedFacts").isEmpty());
            }
        }
    }

    @TestFactory
    Stream<DynamicTest> E01FrozenSamplesUseRealContextWorkerAndLedgerWithoutSemanticScoring() {
        return MemoryV2FixtureLoader.effectCases().stream()
                .map(
                        specification ->
                                DynamicTest.dynamicTest(
                                        specification.path("id").asText(),
                                        () -> exercise(specification)));
    }

    private void exercise(JsonNode specification) throws Exception {
        String id = specification.path("id").asText();
        try (var fixture = database(directory.resolve(id + ".db"))) {
            var properties = properties(directory.resolve(id));
            var files = new MarkdownMemory(fixture.db, properties);
            var loaded = new MemoryV2FixtureLoader(fixture.db, specification).load(files);
            var request = loaded.request();
            var embeddings = mock(EmbeddingClient.class);
            var index = new MemorySearchIndex(fixture.db, files, loaded.clock());
            var vectors = new VectorMemory(fixture.db, embeddings, properties, loaded.clock());
            var context =
                    new MemoryContext(
                            fixture.db,
                            files,
                            new ConversationHistory(fixture.db),
                            new ContextAssembler(BUDGETS, JSON),
                            BUDGETS,
                            loaded.clock());
            var memory =
                    new MemoryService(
                            new MarkdownSearch(fixture.db, index, loaded.clock()),
                            context,
                            vectors,
                            properties,
                            new EvidenceSearch(index, vectors, properties));
            var bundle = memory.assemble(request);
            String answer = bundle.evidence().isEmpty() ? "合成结构回放答复。" : "合成结构回放答复。[S1]";
            var generated = new CopyOnWriteArrayList<GenerationRequest>();
            var dispatched = new CopyOnWriteArrayList<UUID>();
            var delegate = mock(TextGenerator.class);
            when(delegate.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                    .thenAnswer(
                            invocation -> {
                                GenerationRequest generation = invocation.getArgument(0);
                                GenerationObserver observer = invocation.getArgument(1);
                                generated.add(generation);
                                observer.dispatched();
                                dispatched.add(generation.callId());
                                observer.usage(USAGE);
                                return new TextGenerator.Result(answer, null, USAGE);
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
                JsonNode expected = specification.path("expected");
                int calls = expected.path("generationCalls").asInt();
                assertEquals(calls, generated.size(), id + " port attempts");
                assertEquals(calls, dispatched.size(), id + " dispatched");
                assertEquals(
                        calls,
                        fixture.db
                                .list("select id from model_call where run_id=?", loaded.runId())
                                .size(),
                        id + " ledger rows");
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
                assertEquals(
                        calls == 0 ? expected.path("localReply").asText() : answer,
                        reply,
                        id + " outbox");
                verify(delegate, times(calls))
                        .generate(any(GenerationRequest.class), any(GenerationObserver.class));
                String prompt = calls == 0 ? bundle.userPrompt() : generated.get(0).userPrompt();
                for (JsonNode alias : expected.path("include")) {
                    String sourceKey = loaded.sourceKeys().get(alias.asText());
                    assertTrue(
                            bundle.stats().selectedKeys().contains(sourceKey),
                            id + " selected " + alias);
                    assertTrue(
                            prompt.contains(
                                    loaded.events().get(alias.asText()).path("body").asText()),
                            id + " prompt include " + alias);
                }
                for (JsonNode alias : expected.path("exclude")) {
                    assertFalse(
                            bundle.stats()
                                    .selectedKeys()
                                    .contains(loaded.sourceKeys().get(alias.asText())),
                            id + " excluded " + alias);
                    assertFalse(
                            prompt.contains(
                                    loaded.events().get(alias.asText()).path("body").asText()),
                            id + " prompt exclude " + alias);
                }
                assertEquals(1, occurrences(prompt, request.question()), id + " current question");
                assertTrue(bundle.stats().estimatedInputTokens() <= BUDGETS.inputTokens());
                assertEquals(
                        bundle.systemPrompt().getBytes(StandardCharsets.UTF_8).length
                                + prompt.getBytes(StandardCharsets.UTF_8).length
                                + 256,
                        bundle.stats().estimatedInputTokens());
                if (calls > 0) {
                    var actual = generated.get(0);
                    assertEquals(bundle.systemPrompt(), actual.systemPrompt());
                    assertEquals(bundle.userPrompt(), actual.userPrompt());
                    assertEquals(request.scope().groupId(), actual.groupId());
                    assertEquals(request.scope().epoch(), actual.epoch());
                    assertEquals(
                            request.mode() == QueryMode.HISTORY
                                    ? Purpose.MEMORY_QUERY
                                    : Purpose.CHAT,
                            actual.purpose());
                    assertEquals(
                            1,
                            fixture.db
                                    .list(
                                            "select id from model_call where run_id=? and state='SUCCEEDED' and dispatched=true and usage_status='REPORTED'",
                                            loaded.runId())
                                    .size());
                }
                verifyNoInteractions(embeddings);
            } finally {
                worker.close();
            }
        }
    }

    private static BotProperties properties(Path state) {
        var gateway = new BotProperties.Gateway(false, "", "", "effect-fixture", "");
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

    private static void awaitFinished(MemoryV2TestDatabase fixture, UUID run) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (; ; ) {
            var row = fixture.db.one("select state from agent_run where id=?", run);
            if (!Set.of("QUEUED", "RUNNING").contains(row.get("state"))) {
                assertEquals("SUCCEEDED", row.get("state"));
                return;
            }
            if (System.nanoTime() > deadline) {
                fail("Effect structural replay did not finish: " + run);
            }
            Thread.sleep(10);
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
