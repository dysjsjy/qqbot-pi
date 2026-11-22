/**
 * [INPUT]: 依赖临时 SQLite、固定时钟和可控模型回执。
 * [OUTPUT]: 验证维护、清除及迟到回复与用量的状态边界。
 * [POS]: 记忆维护的业务回归测试。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.management.GroupAdministration;
import dev.qqbot.memory.*;
import dev.qqbot.message.AgentWorker;
import dev.qqbot.model.*;
import dev.qqbot.usage.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class T10MaintenanceTest {
    static final Instant NOW = Instant.parse("2026-11-11T04:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    @TempDir Path directory;
    MemoryV2TestDatabase fixture;
    UUID group;
    BotProperties properties;
    MarkdownMemory files;
    MarkdownArchive archive;
    MemorySearchIndex index;
    MemoryProperties prices;

    MemoryV2TestDatabase database() {
        return new MemoryV2TestDatabase(directory.resolve("maintenance.db"));
    }

    @BeforeEach
    void setup() {
        fixture = database();
        group = fixture.group("primary");
        var gateway = new BotProperties.Gateway(false, "", "", "test", "");
        properties =
                new BotProperties(
                        "admin",
                        "",
                        directory,
                        false,
                        new BotProperties.Pi("node", "unused", "test", "test", "KEY", "", 5, ""),
                        new BotProperties.Embedding("", "", "", 0, 0),
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
        prices =
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
                        "test-v1");
        files = new MarkdownMemory(fixture.db, properties);
        archive =
                new MarkdownArchive(
                        fixture.db, files, new MemoryPolicyService(fixture.db), prices, CLOCK);
        index = new MemorySearchIndex(fixture.db, files, CLOCK);
    }

    @AfterEach
    void close() {
        if (fixture != null) {
            fixture.close();
        }
    }

    MemoryMaintenance maintenance(Clock clock) {
        return new MemoryMaintenance(fixture.db, properties, archive, index, clock);
    }

    @Test
    void L01ClearRemovesEveryDerivedSourceAndStatsButKeepsSequenceAndUsage() {
        UUID first = fixture.message(group, "source", "A", "发布安排先备份", 1, NOW.minusSeconds(60));
        UUID second = fixture.message(group, "source-two", "B", "发布安排再迁移", 2, NOW.minusSeconds(30));
        UUID summary = document("SUMMARY", "READY", List.of(first, second));
        document("RAW", "READY", List.of(first));
        UUID running =
                document(
                        "SUMMARY",
                        "RUNNING",
                        List.of(
                                fixture.message(
                                        group,
                                        "running",
                                        "C",
                                        "正在处理的原文",
                                        3,
                                        NOW.minusSeconds(10))));
        files.write(group, "人工背景");
        UUID run = agent(first);
        fixture.db.exec(
                "update agent_run set context_stats=? where id=?",
                fixture.db.json(Map.of("runId", run, "manualHash", "saved-hash")),
                run);
        fixture.db.exec(
                "insert into memory_chunk(id,group_id,message_id,epoch,part_index,body,embedding,model,dimensions) values(?,?,?,2,0,'向量正文','[1]','local',1)",
                UUID.randomUUID(),
                group,
                first);
        fixture.db.exec(
                "insert into outbox(id,group_id,result_key,agent_run_id,epoch,part_index,body,state) values(?,?,?,?,2,0,'旧发送正文','PENDING')",
                UUID.randomUUID(),
                group,
                run.toString(),
                run);
        UUID call = ledger();
        for (int round = 0; round < 3; round++) {
            index.indexPending(group, 100);
        }
        assertTrue(count("memory_search_chunk", group) > 0);
        assertEquals(1, count("memory_search_state", group));
        UUID other = fixture.group("unrelated");
        fixture.message(other, "unrelated", "A", "其他群仍保留", 1, NOW.minusSeconds(5));
        files.write(other, "其他群人工背景");
        index.indexPending(other, 100);
        long sequence =
                ((Number)
                                fixture.db
                                        .one("select event_seq from group_config where id=?", group)
                                        .get("event_seq"))
                        .longValue();
        fixture.db.tx(
                () -> {
                    maintenance(CLOCK).clear(group);
                    return null;
                });
        for (String table :
                List.of(
                        "memory_document",
                        "memory_search_chunk",
                        "memory_search_term",
                        "memory_search_state",
                        "memory_chunk",
                        "group_message")) {
            assertEquals(0, count(table, group), table);
        }
        assertNull(
                fixture.db
                        .one("select context_stats from agent_run where id=?", run)
                        .get("context_stats"));
        assertEquals(
                "CANCELLED",
                fixture.db.one("select state from agent_run where id=?", run).get("state"));
        assertEquals(
                "",
                fixture.db.one("select body from outbox where agent_run_id=?", run).get("body"));
        assertEquals("", files.read(group));
        assertEquals("", files.readDocument(group, summary, "SUMMARY"));
        assertNull(fixture.db.one("select id from memory_document where id=?", running));
        var state = fixture.db.one("select epoch,event_seq from group_config where id=?", group);
        assertEquals(3L, ((Number) state.get("epoch")).longValue());
        assertEquals(sequence, ((Number) state.get("event_seq")).longValue());
        assertNotNull(fixture.db.one("select id from model_call where id=?", call));
        assertEquals(
                1L,
                new UsageQueries(fixture.db, prices, CLOCK)
                        .daily(group, LocalDate.of(2026, 11, 11))
                        .totals()
                        .calls());
        assertTrue(
                index.lexical(
                                new MemoryScope(group, 3, null, sequence + 1, NOW, NOW),
                                "发布安排",
                                QueryMode.HISTORY,
                                200)
                        .evidence()
                        .isEmpty());
        assertEquals(1, count("group_message", other));
        assertTrue(count("memory_search_chunk", other) > 0);
        assertEquals("其他群人工背景", files.read(other));
    }

    @Test
    void L02DeleteHandlesNewForeignKeysAndPreservesDetachedLedger() {
        UUID message = fixture.message(group, "source", "A", "被删除的正文", 1, NOW.minusSeconds(60)),
                summary = document("SUMMARY", "READY", List.of(message));
        files.write(group, "被删除的人工记忆");
        UUID run = agent(message);
        UUID call = ledger();
        index.indexPending(group, 100);
        fixture.db.tx(
                () -> {
                    new GroupAdministration(fixture.db, properties, files)
                            .delete(group, "primary", 0);
                    return null;
                });
        for (String table :
                List.of(
                        "memory_document",
                        "memory_policy",
                        "memory_search_chunk",
                        "memory_search_term",
                        "memory_search_state",
                        "memory_chunk",
                        "group_message",
                        "conversation",
                        "agent_run",
                        "outbox")) {
            assertEquals(0, count(table, group), table);
        }
        assertNull(fixture.db.one("select id from group_config where id=?", group));
        assertNull(fixture.db.one("select id from agent_run where id=?", run));
        assertNull(
                fixture.db.one("select group_id from model_call where id=?", call).get("group_id"));
        assertEquals(
                "0.00012200",
                fixture.db
                        .one("select reported_cost from model_call where id=?", call)
                        .get("reported_cost")
                        .toString());
        assertEquals(
                404,
                assertThrows(
                                ResponseStatusException.class,
                                () -> new MemoryPolicyService(fixture.db).settings(group))
                        .getStatusCode()
                        .value());
        assertEquals("", files.readDocument(group, summary, "SUMMARY"));
        try (var store =
                new RawSegmentStore(
                        directory, RawArchiveProperties.defaults(), new ObjectMapper())) {
            var raw =
                    new RawArchiveService(
                            fixture.db, store, RawArchiveProperties.defaults(), CLOCK);
            new RawArchiveCollector(fixture.db, store, raw, CLOCK).runOnce();
            try (var paths =
                    java.nio.file.Files.walk(
                            directory.resolve("memory").resolve(group.toString()))) {
                assertEquals(0, paths.filter(java.nio.file.Files::isRegularFile).count());
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }
    }

    @Test
    void L02DispatchedWorkerCannotRecreateDeletedGroupButFinishesItsDetachedUsage()
            throws Exception {
        UUID message =
                fixture.message(group, "late-question", "A", "继续讨论", 1, NOW.minusSeconds(60));
        UUID run = agent(message);
        fixture.db.exec(
                "update agent_run set state='QUEUED',created_at=?,context_occurred_at=? where id=?",
                NOW,
                NOW.minusSeconds(60),
                run);
        var embedding = mock(EmbeddingClient.class);
        var vectors = new VectorMemory(fixture.db, embedding, properties, CLOCK);
        var context =
                new MemoryContext(
                        fixture.db,
                        files,
                        new ConversationHistory(fixture.db),
                        new ContextAssembler(prices, new ObjectMapper()),
                        prices,
                        CLOCK);
        var memory =
                new MemoryService(
                        new MarkdownSearch(fixture.db, index, CLOCK),
                        context,
                        vectors,
                        properties,
                        new EvidenceSearch(index, vectors, properties));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var delegate = mock(TextGenerator.class);
        when(delegate.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenAnswer(
                        invocation -> {
                            GenerationObserver observer = invocation.getArgument(1);
                            observer.dispatched();
                            entered.countDown();
                            assertTrue(release.await(10, TimeUnit.SECONDS));
                            var usage =
                                    new ModelUsage(100L, 10L, 20L, 0L, 5L, UsageStatus.REPORTED);
                            observer.usage(usage);
                            return new TextGenerator.Result("删除后迟到正文", null, usage);
                        });
        var worker =
                new AgentWorker(
                        fixture.db,
                        new MeteredTextGenerator(
                                delegate,
                                new ModelCallStore(fixture.db, properties, prices, CLOCK)),
                        memory,
                        new AgentWorker.Settings(properties, prices, CLOCK));
        worker.setTraces(spans -> completed.countDown());
        try {
            worker.tick();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var running =
                    fixture.db.one(
                            "select id,state,dispatched from model_call where run_id=?", run);
            assertNotNull(running);
            assertEquals("RUNNING", running.get("state"));
            assertEquals(true, running.get("dispatched"));
            fixture.db.tx(
                    () -> {
                        new GroupAdministration(fixture.db, properties, files)
                                .delete(group, "primary", 0);
                        return null;
                    });
            assertNull(
                    fixture.db
                            .one("select group_id from model_call where id=?", running.get("id"))
                            .get("group_id"));
            release.countDown();
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            for (String table :
                    List.of(
                            "group_config",
                            "group_message",
                            "conversation",
                            "agent_run",
                            "outbox",
                            "memory_document",
                            "memory_search_chunk")) {
                if (table.equals("group_config")) {
                    assertNull(fixture.db.one("select id from group_config where id=?", group));
                } else {
                    assertEquals(0, count(table, group), table);
                }
            }
            var accounted =
                    fixture.db.one("select * from model_call where id=?", running.get("id"));
            assertNull(accounted.get("group_id"));
            assertEquals("SUCCEEDED", accounted.get("state"));
            assertEquals("REPORTED", accounted.get("usage_status"));
            assertEquals("0.00012200", accounted.get("reported_cost").toString());
            assertEquals(
                    404,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            new GroupAdministration(fixture.db, properties, files)
                                                    .get(group))
                            .getStatusCode()
                            .value());
            verify(delegate, times(1))
                    .generate(any(GenerationRequest.class), any(GenerationObserver.class));
            verifyNoInteractions(embedding);
        } finally {
            release.countDown();
            worker.close();
        }
    }

    @Test
    void L03OneExpiredSourceInvalidatesWholeHundredSourceSummaryAndItsIndex() {
        var sources = new ArrayList<UUID>();
        for (int position = 1; position <= 100; position++) {
            sources.add(
                    fixture.message(
                            group,
                            "source-" + position,
                            "A",
                            "独立原文 " + position,
                            position,
                            position == 1
                                    ? NOW.minus(Duration.ofDays(30))
                                    : NOW.minusSeconds(120 - position)));
        }
        UUID summary = document("SUMMARY", "READY", sources);
        files.writeDocument(group, summary, "SUMMARY", "发布安排由原始来源共同支持");
        files.write(group, "人工背景不会自动过期");
        for (int round = 0; round < 4; round++) {
            index.indexPending(group, 100);
        }
        var scope = new MemoryScope(group, 2, null, 101, NOW, NOW);
        assertEquals(
                List.of(summary),
                index.lexical(scope, "发布安排", QueryMode.HISTORY, 200).evidence().stream()
                        .map(value -> UUID.fromString(value.sourceKey().split(":")[1]))
                        .toList());
        new MemoryMaintenance(
                        fixture.db,
                        properties,
                        archive,
                        index,
                        Clock.fixed(NOW.plusMillis(1), ZoneOffset.UTC),
                        new RetentionProperties(30, 30, 30))
                .cleanup();
        assertNull(fixture.db.one("select id from memory_document where id=?", summary));
        assertEquals("", files.readDocument(group, summary, "SUMMARY"));
        assertEquals(99, count("group_message", group));
        assertNull(fixture.db.one("select id from group_message where id=?", sources.get(0)));
        assertNotNull(fixture.db.one("select id from group_message where id=?", sources.get(1)));
        assertTrue(index.lexical(scope, "发布安排", QueryMode.HISTORY, 200).evidence().isEmpty());
        assertEquals(
                0,
                fixture.db
                        .list("select id from memory_search_chunk where document_id=?", summary)
                        .size());
        assertEquals(
                0,
                fixture.db
                        .list(
                                "select id from memory_search_chunk where source_key=?",
                                "message:" + sources.get(0))
                        .size());
        assertEquals("人工背景不会自动过期", files.read(group));
        assertFalse(index.lexical(scope, "人工背景", QueryMode.HISTORY, 200).evidence().isEmpty());
        assertEquals(
                100L,
                ((Number)
                                fixture.db
                                        .one("select event_seq from group_config where id=?", group)
                                        .get("event_seq"))
                        .longValue());
    }

    @Test
    void L08ScheduledIndexerRebuildRestoresSameEvidenceWithoutChangingSources() {
        UUID message = fixture.message(group, "source", "A", "发布安排先备份", 1, NOW.minusSeconds(60));
        files.write(group, "人工背景");
        var worker = new MemorySearchWorker(fixture.db, index);
        worker.tick();
        var scope = new MemoryScope(group, 2, null, 2, NOW, NOW);
        var before = index.lexical(scope, "发布安排", QueryMode.HISTORY, 200).evidence();
        assertFalse(before.isEmpty());
        index.rebuild(group);
        assertEquals(0, count("memory_search_chunk", group));
        assertEquals(1, count("group_message", group));
        worker.tick();
        assertEquals(before, index.lexical(scope, "发布安排", QueryMode.HISTORY, 200).evidence());
        assertNotNull(fixture.db.one("select id from group_message where id=?", message));
        assertEquals("人工背景", files.read(group));
        assertTrue(fixture.db.list("select id from model_call").isEmpty());
    }

    UUID document(String kind, String state, List<UUID> sources) {
        UUID id = UUID.randomUUID();
        fixture.db.exec(
                "insert into memory_document(id,group_id,epoch,kind,state,source_count,strategy_version) values(?,?,2,?,?,?,2)",
                id,
                group,
                kind,
                state,
                sources.size());
        for (UUID message : sources) {
            fixture.db.exec(
                    "insert into memory_document_source(document_id,message_id,kind) values(?,?,?)",
                    id,
                    message,
                    kind);
        }
        if (state.equals("READY")) {
            files.writeDocument(group, id, kind, "保留的文档正文");
        }
        return id;
    }

    UUID agent(UUID message) {
        UUID conversation = UUID.randomUUID(), run = UUID.randomUUID();
        fixture.db.exec(
                "insert into conversation(id,group_id,sender_id) values(?,?,'A')",
                conversation,
                group);
        fixture.db.exec(
                "insert into agent_run(id,group_id,message_id,conversation_id,generation,epoch,kind,prompt,state,context_seq,context_occurred_at) values(?,?,?,?,0,2,'CHAT','原始问题','RUNNING',1,?)",
                run,
                group,
                message,
                conversation,
                NOW);
        return run;
    }

    UUID ledger() {
        var store = new ModelCallStore(fixture.db, properties, prices, CLOCK);
        var request =
                new GenerationRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        group,
                        2,
                        Purpose.CHAT,
                        "s",
                        "q",
                        8000,
                        800,
                        30);
        store.begin(request);
        store.dispatched(request.callId());
        store.usage(request.callId(), new ModelUsage(100L, 10L, 20L, 0L, 5L, UsageStatus.REPORTED));
        store.finish(request.callId(), "SUCCEEDED", null);
        return request.callId();
    }

    long count(String table, UUID id) {
        return ((Number)
                        fixture.db
                                .one("select count(*) n from " + table + " where group_id=?", id)
                                .get("n"))
                .longValue();
    }
}
