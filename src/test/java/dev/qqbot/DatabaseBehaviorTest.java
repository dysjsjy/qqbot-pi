/**
 * [INPUT]: 使用 SQLite 夹具、固定业务时钟和本地替身。
 * [OUTPUT]: 验证持久化事务、调度、记忆与留存边界。
 * [POS]: 数据库集成测试共享场景；不连接实际平台。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.channel.*;
import dev.qqbot.delivery.MessageTransport;
import dev.qqbot.delivery.MessageTransport.*;
import dev.qqbot.memory.*;
import dev.qqbot.message.*;
import dev.qqbot.model.GenerationObserver;
import dev.qqbot.model.GenerationRequest;
import dev.qqbot.persistence.Db;
import dev.qqbot.pi.PiRuntime;
import dev.qqbot.schedule.DailySummaryService;
import dev.qqbot.schedule.DailySummaryWorker;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;

/** Shared persistence behavior, exercised by both database backends. */
abstract class DatabaseBehaviorTest {
    static Db db;
    static BotProperties props;
    final Clock clock = Clock.fixed(Instant.parse("2026-11-07T01:00:00Z"), ZoneOffset.UTC);
    DailySummaryService daily;
    IngestService ingest;
    MemoryService memory;
    EmbeddingClient embeddings;
    VectorMemory vectors;
    MemoryMaintenance maintenance;
    RawSegmentStore rawStore;
    RawArchiveService rawArchive;

    @BeforeEach
    void reset() {
        clearTables();
        daily = new DailySummaryService(db, clock);
        ingest = TestServices.ingest(db, daily, props);
        embeddings = mock(EmbeddingClient.class);
        when(embeddings.embed(anyList()))
                .thenAnswer(
                        i ->
                                ((List<?>) i.getArgument(0))
                                        .stream().map(x -> new float[] {1, 0, 0}).toList());
        memory = TestServices.memory(db, embeddings, props);
        vectors = new VectorMemory(db, embeddings, props);
        rawStore = new RawSegmentStore(props, RawArchiveProperties.defaults(), new ObjectMapper());
        rawArchive =
                new RawArchiveService(
                        db, rawStore, RawArchiveProperties.defaults(), Clock.systemUTC());
        var files = new MarkdownMemory(db, props);
        maintenance =
                new MemoryMaintenance(
                        db,
                        props,
                        archive(),
                        new MemorySearchIndex(db, files, Clock.systemUTC()),
                        Clock.systemUTC());
    }

    @AfterEach
    void closeRaw() {
        if (rawStore != null) {
            rawStore.close();
        }
    }

    void clearTables() {
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
                        "group_config",
                        "admin_audit",
                        "app_metadata")) {
            db.exec("delete from " + table);
        }
    }

    UUID group(String name) {
        UUID id = UUID.randomUUID();
        db.exec(
                "insert into group_config(id,channel,account_id,external_id,name,enabled,collection_since,admin_ids) values(?,'QQ','10001',?,?,true,?,'admin')",
                id,
                name,
                name,
                clock.instant().minus(Duration.ofDays(2)));
        return id;
    }

    GroupIngestRequest event(String group, String key, String text, boolean mention) {
        return new GroupIngestRequest(
                "QQ", "10001", group, key, "user", "小明", Instant.now(), text, mention, false);
    }

    GroupIngestRequest privateEvent(String user, String key, String text) {
        return new GroupIngestRequest(
                "QQ",
                "10001",
                user,
                key,
                user,
                "用户",
                Instant.now(),
                text,
                false,
                false,
                "TEXT",
                true);
    }

    long count(String table) {
        return ((Number) db.one("select count(*) n from " + table).get("n")).longValue();
    }

    Map<String, Object> awaitAgent(UUID messageId) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (true) {
            var job = db.one("select * from agent_run where message_id=?", messageId);
            if (!Set.of("QUEUED", "RUNNING").contains(job.get("state"))) {
                return job;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("agent did not finish");
            }
            Thread.sleep(20);
        }
    }

    BotProperties timings(int queueSeconds, int requestSeconds) {
        var pi = props.pi();
        return new BotProperties(
                props.adminUsername(),
                props.adminPasswordHash(),
                props.stateDir(),
                false,
                new BotProperties.Pi(
                        pi.node(),
                        pi.cli(),
                        pi.provider(),
                        pi.model(),
                        pi.keyEnv(),
                        pi.apiKey(),
                        pi.timeoutSeconds(),
                        pi.baseUrl(),
                        300,
                        queueSeconds,
                        requestSeconds),
                props.embedding(),
                props.qq(),
                props.weixin(),
                props.rag());
    }

    void raw(UUID group, Instant at, String text) {
        db.tx(
                () -> {
                    var state =
                            db.one("select epoch,event_seq from group_config where id=?", group);
                    long sequence = ((Number) state.get("event_seq")).longValue() + 1;
                    db.exec(
                            "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,received_at,epoch,kind,event_seq) values(?,?,?,'u','成员',?,?,?,?, 'TEXT',?)",
                            UUID.randomUUID(),
                            group,
                            UUID.randomUUID().toString(),
                            text,
                            at,
                            clock.instant().minusSeconds(1),
                            state.get("epoch"),
                            sequence);
                    db.exec("update group_config set event_seq=? where id=?", sequence, group);
                    return null;
                });
    }

    void indexMemory(UUID group) {
        var index = new MemorySearchIndex(db, new MarkdownMemory(db, props), Clock.systemUTC());
        for (int batch = 0; batch < 100; batch++) {
            if (index.indexPending(group, 100) == 0) {
                return;
            }
        }
        fail("memory index did not drain");
    }

    @Test
    void markdownMemoryIsScopedPersistentAndCleared() {
        UUID a = group("12345"), b = group("23456");
        var files = new MarkdownMemory(db, props);
        files.write(a, "# 本群\n长期背景");
        files.write(b, "# 另一个群");
        assertEquals("# 本群\n长期背景", new MarkdownMemory(db, props).read(a));
        assertEquals("# 另一个群", files.read(b));
        db.tx(
                () -> {
                    maintenance.clear(a);
                    return null;
                });
        assertEquals("", files.read(a));
        assertEquals("# 另一个群", files.read(b));
    }

    dev.qqbot.memory.MarkdownArchive archive() {
        var archive = new MarkdownArchive(db, new MarkdownMemory(db, props));
        archive.setRawArchive(rawArchive);
        return archive;
    }

    void drainArchive(dev.qqbot.memory.MarkdownArchive archive, PiRuntime pi) {
        for (var group : db.list("select id from group_config where enabled")) {
            rawArchive.archiveBatch((UUID) group.get("id"));
        }
        for (int count = 0; count < 30; count++) {
            var job = archive.claim();
            if (job == null) {
                return;
            }
            archive.process(job, pi);
        }
        fail("archive did not drain");
    }

    @Test
    void rawMarkdownPreservesTextIsSearchableAndDoesNotCallAi() {
        UUID first = group("12345"), other = group("23456");
        String body = "发布安排：周五 10:00\n原样保留 **Markdown** 和 <script>文本</script> 😀";
        raw(first, Instant.now(), body);
        raw(other, Instant.now(), "发布安排：另一群秘密");
        var archive = archive();
        assertEquals("RAW", archive.status(first).get("mode"));
        assertEquals(0, archive.prepare());
        assertEquals(0, archive.prepare());
        var pi = mock(PiRuntime.class);
        drainArchive(archive, pi);
        verifyNoInteractions(pi);
        var document = archive.documents(first, 10).get(0);
        String content = (String) archive.document(first, (UUID) document.get("id")).get("content");
        assertTrue(content.contains(body));
        assertFalse(content.contains("另一群秘密"));
        var results = memory.search(first, "发布安排", 5, null);
        assertEquals(1, results.size());
        assertTrue(results.get(0).body().contains("周五"));
        assertTrue(
                memory.search(first, "另一群秘密", 5, null).stream()
                        .noneMatch(source -> source.body().contains("另一群秘密")));
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> archive.document(other, (UUID) document.get("id")));
        assertEquals("SEGMENT_V1", document.get("storage"));
        assertFalse(
                java.nio.file.Files.exists(
                        props.stateDir()
                                .resolve("memory")
                                .resolve(first.toString())
                                .resolve("raw")
                                .resolve(document.get("id") + ".md")));
    }

    @Test
    void summaryWaitsForEveryHundredAndRetainsManualAndRawMemory() {
        UUID id = group("12345");
        var archive = archive();
        archive.configure(id, "SUMMARY");
        var files = new MarkdownMemory(db, props);
        files.write(id, "# 管理员维护，禁止覆盖");
        for (int index = 0; index < 99; index++) {
            raw(id, Instant.now(), "事实" + index);
        }
        var pi = mock(PiRuntime.class);
        when(pi.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenAnswer(
                        invocation -> {
                            GenerationObserver observer = invocation.getArgument(1);
                            observer.dispatched();
                            return new PiRuntime.Result("## 决定\n发布已确定", null);
                        });
        archive.prepare();
        drainArchive(archive, pi);
        verifyNoInteractions(pi);
        raw(id, Instant.now(), "第100条");
        archive.prepare();
        drainArchive(archive, pi);
        verify(pi, times(3))
                .generate(
                        argThat(
                                request ->
                                        request.timeoutSeconds() > 60
                                                && dev.qqbot.model.TokenEstimator.estimateInput(
                                                                request.systemPrompt(),
                                                                request.userPrompt())
                                                        <= request.maxInputTokens()),
                        any(GenerationObserver.class));
        for (int index = 100; index < 200; index++) {
            raw(id, Instant.now(), "事实" + index);
        }
        archive.prepare();
        drainArchive(archive, pi);
        archive.prepare();
        drainArchive(archive, pi);
        verify(pi, times(6)).generate(any(GenerationRequest.class), any(GenerationObserver.class));
        var summaries =
                db.list("select * from memory_document where group_id=? and kind='SUMMARY'", id);
        assertEquals(2, summaries.size());
        for (var summary : summaries) {
            assertEquals(100, ((Number) summary.get("source_count")).intValue());
            assertEquals("READY", summary.get("state"));
            assertEquals(
                    100,
                    ((List<?>) archive.document(id, (UUID) summary.get("id")).get("sources"))
                            .size());
        }
        assertEquals(
                200,
                ((Number)
                                db.one(
                                                "select count(*) n from memory_document_source where kind='RAW'")
                                        .get("n"))
                        .intValue());
        assertEquals("# 管理员维护，禁止覆盖", files.read(id));
        indexMemory(id);
        assertTrue(
                memory.search(id, "发布", 5, null).stream()
                        .anyMatch(source -> source.name().contains("自动概括")));
        var trigger = db.tx(() -> ingest.ingest(event("12345", "after-summary", "总结之后的问题", true)));
        assertTrue(memory.context(id, trigger.messageId()).contains("发布已确定"));
    }

    @Test
    void repeatedEventsCommandsAndNonTextDoNotDuplicateMarkdown() {
        UUID id = group("12345");
        var archive = archive();
        db.tx(() -> ingest.ingest(event("12345", "once", "应只归档一次", false)));
        db.tx(() -> ingest.ingest(event("12345", "once", "应只归档一次", false)));
        db.tx(() -> ingest.ingest(event("12345", "help", "/help", false)));
        db.tx(
                () ->
                        ingest.ingest(
                                new GroupIngestRequest(
                                        "QQ",
                                        "10001",
                                        "12345",
                                        "image",
                                        "user",
                                        "成员",
                                        Instant.now(),
                                        "",
                                        false,
                                        false,
                                        "NON_TEXT")));
        archive.prepare();
        drainArchive(archive, mock(PiRuntime.class));
        assertEquals(1, archive.documents(id, 10).size());
        assertEquals(1, ((Number) archive.documents(id, 10).get(0).get("source_count")).intValue());
        assertEquals(0, archive.prepare());
    }

    @Test
    void failedSummaryCanRetryWithoutLosingItsSourceBatch() {
        UUID id = group("12345");
        var archive = archive();
        archive.configure(id, "SUMMARY");
        for (int index = 0; index < 100; index++) {
            raw(id, Instant.now(), "消息" + index);
        }
        var broken = mock(PiRuntime.class);
        when(broken.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenThrow(new IllegalStateException("offline"));
        archive.prepare();
        drainArchive(archive, broken);
        var failed = db.one("select * from memory_document where kind='SUMMARY'");
        assertEquals("FAILED", failed.get("state"));
        assertEquals(
                100,
                ((Number)
                                db.one(
                                                "select count(*) n from memory_document_source where kind='SUMMARY'")
                                        .get("n"))
                        .intValue());
        assertEquals(0, archive.prepare());
        var recovered = mock(PiRuntime.class);
        when(recovered.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenAnswer(
                        invocation -> {
                            GenerationObserver observer = invocation.getArgument(1);
                            observer.dispatched();
                            return new PiRuntime.Result("已恢复的摘要", null);
                        });
        archive.retry(id, (UUID) failed.get("id"));
        drainArchive(archive, recovered);
        var result = archive.document(id, (UUID) failed.get("id"));
        assertEquals("READY", result.get("state"));
        assertEquals(2, ((Number) result.get("attempt")).intValue());
        assertTrue(result.get("content").toString().contains("已恢复"));
        assertEquals(
                1,
                ((Number)
                                db.one(
                                                "select count(*) n from memory_document where kind='SUMMARY'")
                                        .get("n"))
                        .intValue());
    }

    @Test
    void clearAndRetentionRemoveGeneratedFilesAndPreventStaleCompletion() {
        UUID id = group("12345");
        var archive = archive();
        raw(id, Instant.now(), "将清除文本");
        drainArchive(archive, mock(PiRuntime.class));
        UUID stale = UUID.randomUUID();
        db.exec(
                "insert into memory_document(id,group_id,epoch,kind,state,source_count) values(?,?,0,'SUMMARY','RUNNING',1)",
                stale,
                id);
        db.exec(
                "insert into memory_document_source(document_id,message_id,kind) select ?,id,'SUMMARY' from group_message where group_id=?",
                stale,
                id);
        var oldJob = db.one("select * from memory_document where id=?", stale);
        db.tx(
                () -> {
                    maintenance.clear(id);
                    return null;
                });
        archive.process(oldJob, mock(PiRuntime.class));
        assertTrue(archive.documents(id, 10).isEmpty());
        assertEquals(
                404,
                assertThrows(
                                org.springframework.web.server.ResponseStatusException.class,
                                () -> archive.document(id, stale))
                        .getStatusCode()
                        .value());
        new RawArchiveCollector(db, rawStore, rawArchive, Clock.systemUTC()).runOnce();
        UUID old = UUID.randomUUID();
        long retainedSequence =
                ((Number)
                                        db.one("select event_seq from group_config where id=?", id)
                                                .get("event_seq"))
                                .longValue()
                        + 1;
        db.exec(
                "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,epoch,kind,event_seq) values(?,?,'retention','u','成员','过期文本',?,1,'TEXT',?)",
                old,
                id,
                Instant.now().minus(Duration.ofDays(29)),
                retainedSequence);
        db.exec("update group_config set event_seq=? where id=?", retainedSequence, id);
        archive.prepare();
        drainArchive(archive, mock(PiRuntime.class));
        var document = archive.documents(id, 10).get(0);
        assertEquals("READY", document.get("state"));
        new MemoryMaintenance(
                        db,
                        props,
                        archive,
                        new MemorySearchIndex(db, new MarkdownMemory(db, props), Clock.systemUTC()),
                        Clock.offset(Clock.systemUTC(), Duration.ofDays(2)),
                        new RetentionProperties(30, 30, 30))
                .cleanup();
        assertTrue(archive.documents(id, 10).isEmpty());
        assertEquals(
                "",
                new MarkdownMemory(db, props).readDocument(id, (UUID) document.get("id"), "RAW"));
    }

    @Test
    void contextReloadsManualMemoryAndUsesOnlyTheLastTwentyEarlierMessages() {
        UUID id = group("12345"), other = group("23456");
        Instant start = Instant.now().minusSeconds(100);
        for (int index = 0; index < 25; index++) {
            raw(id, start.plusSeconds(index), "历史标记" + String.format("%02d", index));
        }
        raw(other, start.plusSeconds(26), "其他群绝不能出现");
        var receipt = db.tx(() -> ingest.ingest(event("12345", "current-context", "当前提问", true)));
        raw(id, Instant.now().plusSeconds(10), "未来消息绝不能出现");
        var files = new MarkdownMemory(db, props);
        files.write(id, "第一版背景");
        String context = memory.context(id, receipt.messageId());
        assertTrue(context.contains("第一版背景"));
        assertEquals(
                20, context.lines().filter(line -> line.startsWith("{\"role\":\"USER\"")).count());
        assertFalse(context.contains("历史标记04"));
        assertTrue(context.contains("历史标记05"));
        assertTrue(context.contains("历史标记24"));
        assertTrue(context.indexOf("历史标记05") < context.indexOf("历史标记24"));
        assertFalse(context.contains("当前提问"));
        assertFalse(context.contains("其他群"));
        assertFalse(context.contains("未来消息"));
        files.write(id, "第二版背景");
        assertTrue(memory.context(id, receipt.messageId()).contains("第二版背景"));
    }

    @Test
    void markdownSearchWorksWithoutEmbeddingsAndWithChineseAndEnglish() {
        UUID id = group("12345");
        new MarkdownMemory(db, props).write(id, "# 发布安排\n负责人张三，周五发布。\nRelease owner Alice.");
        var noRag =
                new BotProperties(
                        props.adminUsername(),
                        props.adminPasswordHash(),
                        props.stateDir(),
                        false,
                        props.pi(),
                        props.embedding(),
                        props.qq(),
                        props.weixin(),
                        new BotProperties.Rag(false));
        var embeddings = mock(EmbeddingClient.class);
        var localMemory = TestServices.memory(db, embeddings, noRag);
        indexMemory(id);
        assertFalse(localMemory.search(id, "发布安排", 5).isEmpty());
        assertFalse(localMemory.search(id, "RELEASE OWNER", 5).isEmpty());
        assertTrue(localMemory.search(id, "不存在的关键词", 5).isEmpty());
        verifyNoInteractions(embeddings);
    }

    @Test
    void vectorFailureDoesNotDisableMarkdownAndSearchKeepsZeroOverlapChunks() {
        UUID id = group("12345");
        new MarkdownMemory(db, props).write(id, "x".repeat(997) + "发布安排：周五上线");
        var broken = mock(EmbeddingClient.class);
        when(broken.embed(anyList())).thenThrow(new IllegalStateException("unavailable"));
        var enhanced = TestServices.memory(db, broken, props);
        indexMemory(id);
        var results = enhanced.search(id, "发布安排", 5);
        assertEquals(1, results.size());
        assertEquals("MEMORY.md", results.get(0).id());
        assertTrue(results.get(0).body().contains("发布安"));
        assertTrue(results.get(0).body().codePointCount(0, results.get(0).body().length()) <= 500);
        assertTrue(new MarkdownMemory(db, props).read(id).contains("发布安排"));
    }

    @Test
    void summaryRejectsReferencesOutsideItsSourceBatch() {
        UUID id = group("12345");
        var archive = archive();
        archive.configure(id, "SUMMARY");
        for (int index = 0; index < 100; index++) {
            raw(id, Instant.now(), "事实" + index);
        }
        var pi = mock(PiRuntime.class);
        when(pi.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenAnswer(
                        invocation -> {
                            GenerationObserver observer = invocation.getArgument(1);
                            observer.dispatched();
                            return new PiRuntime.Result("错误引用 [" + UUID.randomUUID() + "]", null);
                        });
        archive.prepare();
        drainArchive(archive, pi);
        var result = db.one("select state,error_code from memory_document where kind='SUMMARY'");
        assertEquals("FAILED", result.get("state"));
        assertEquals("MEMORY_INVALID_CITATION", result.get("error_code"));
    }

    @Test
    void summaryRunsInBackgroundWithoutHoldingTheIngestionLock() throws Exception {
        UUID id = group("12345");
        var archive = archive();
        archive.configure(id, "SUMMARY");
        for (int index = 0; index < 100; index++) {
            raw(id, Instant.now(), "消息" + index);
        }
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pi = mock(PiRuntime.class);
        when(pi.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenAnswer(
                        call -> {
                            GenerationObserver observer = call.getArgument(1);
                            observer.dispatched();
                            entered.countDown();
                            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                            return new PiRuntime.Result("摘要", null);
                        });
        var worker = new dev.qqbot.memory.MarkdownMemoryWorker(archive, pi);
        try {
            worker.tick();
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTimeout(
                    java.time.Duration.ofSeconds(2),
                    () ->
                            db.tx(
                                    () ->
                                            ingest.ingest(
                                                    event(
                                                            "12345",
                                                            "while-summary",
                                                            "概括时的新消息",
                                                            false))));
            assertEquals(101, count("group_message"));
        } finally {
            release.countDown();
            worker.close();
        }
    }

    @Test
    void replyModesRespectDeduplicationAndCommands() {
        UUID id = group("12345");
        db.exec(
                "insert into reply_policy(group_id,mode,probability,energy_gain,energy_cost) values(?,'PROBABILITY',0,1,2)",
                id);
        db.tx(() -> ingest.ingest(event("12345", "zero", "普通消息", false)));
        assertEquals(0, count("agent_run"));
        db.exec("update reply_policy set probability=1 where group_id=?", id);
        db.tx(() -> ingest.ingest(event("12345", "one", "普通消息", false)));
        db.tx(() -> ingest.ingest(event("12345", "one", "普通消息", false)));
        assertEquals(1, count("agent_run"));
        db.exec("update reply_policy set mode='ENERGY',energy=0 where group_id=?", id);
        db.tx(() -> ingest.ingest(event("12345", "e1", "普通消息", false)));
        db.tx(() -> ingest.ingest(event("12345", "e1", "普通消息", false)));
        assertEquals(
                1,
                ((Number)
                                db.one("select energy from reply_policy where group_id=?", id)
                                        .get("energy"))
                        .intValue());
        db.tx(() -> ingest.ingest(event("12345", "e2", "普通消息", false)));
        assertEquals(2, count("agent_run"));
        assertEquals(
                0,
                ((Number)
                                db.one("select energy from reply_policy where group_id=?", id)
                                        .get("energy"))
                        .intValue());
        db.tx(() -> ingest.ingest(event("12345", "help", "/help", false)));
        assertEquals(1, count("outbox"));
    }

    @Test
    void energyIsNotSpentWhenQueueIsFull() {
        UUID id = group("12345");
        db.exec(
                "insert into reply_policy(group_id,mode,energy_gain,energy_cost) values(?,'ENERGY',1,1)",
                id);
        for (int i = 0; i < 5; i++) {
            String key = "queued" + i;
            db.tx(() -> ingest.ingest(event("12345", key, "你好", true)));
        }
        db.tx(() -> ingest.ingest(event("12345", "overflow", "普通消息", false)));
        assertEquals(5, count("agent_run"));
        assertEquals(
                1,
                ((Number)
                                db.one("select energy from reply_policy where group_id=?", id)
                                        .get("energy"))
                        .intValue());
    }

    @Test
    void collectionDeduplicatesAndDoesNotReplyToOrdinaryText() {
        group("12345");
        for (int i = 0; i < 100; i++) {
            final int n = i;
            for (int j = 0; j < 3; j++) {
                db.tx(
                        () ->
                                ingest.ingest(
                                        new GroupIngestRequest(
                                                "QQ",
                                                "10001",
                                                "12345",
                                                "fixed" + n,
                                                "user" + n,
                                                "成员",
                                                Instant.now(),
                                                "普通消息" + n,
                                                n < 10,
                                                false)));
            }
        }
        assertEquals(100, count("group_message"));
        assertEquals(10, count("agent_run"));
        db.tx(() -> ingest.ingest(event("not-authorized", "x", "秘密", true)));
        assertEquals(100, count("group_message"));
        var self = event("12345", "self", "自己的消息", true);
        db.tx(
                () ->
                        ingest.ingest(
                                new GroupIngestRequest(
                                        self.channel(),
                                        self.accountId(),
                                        self.groupId(),
                                        self.messageId(),
                                        self.senderId(),
                                        self.senderName(),
                                        self.occurredAt(),
                                        self.text(),
                                        true,
                                        true)));
        assertEquals(100, count("group_message"));
    }

    @Test
    void privateScopesAreIsolatedReplyWithoutMentionAndCanBeResetAndPaused() {
        UUID existingGroup = group("20001");
        var first = db.tx(() -> ingest.ingest(privateEvent("20001", "same-key", "第一个用户秘密")));
        var other = db.tx(() -> ingest.ingest(privateEvent("20002", "same-key", "第二个用户秘密")));
        var duplicate = db.tx(() -> ingest.ingest(privateEvent("20001", "same-key", "第一个用户秘密")));
        assertNotEquals(existingGroup, first.groupId());
        assertNotEquals(other.groupId(), first.groupId());
        assertTrue(duplicate.duplicate());
        assertEquals(2, count("agent_run"));
        var scope = db.one("select * from group_config where id=?", first.groupId());
        assertEquals("PRIVATE", scope.get("chat_type"));
        assertEquals("20001", scope.get("target_id"));
        assertEquals(true, scope.get("enabled"));
        db.tx(() -> ingest.ingest(privateEvent("20001", "reset", "/new")));
        assertEquals(
                "CANCELLED",
                db.one("select state from agent_run where group_id=?", first.groupId())
                        .get("state"));
        assertEquals(
                "QUEUED",
                db.one("select state from agent_run where group_id=?", other.groupId())
                        .get("state"));
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> daily.trigger(first.groupId(), "WEB"));
        db.exec("update group_config set enabled=false where id=?", first.groupId());
        var paused = db.tx(() -> ingest.ingest(privateEvent("20001", "paused", "不要回复")));
        assertFalse(paused.accepted());
        assertEquals(3, count("group_message"));
    }

    @Test
    void privateOutboxUsesPrivateSenderAndNeverGroupEndpoint() {
        var receipt = db.tx(() -> ingest.ingest(privateEvent("20001", "help", "/help")));
        var sender = mock(MessageTransport.class);
        when(sender.send(any(Message.class)))
                .thenReturn(new Delivery(State.ACCEPTED, "private-receipt", null));
        new OutboxWorker(db, sender, props).tick();
        verify(sender)
                .send(
                        argThat(
                                message ->
                                        message.privateChat()
                                                && message.channel().equals("QQ")
                                                && message.target().equals("20001")
                                                && message.text().contains("私聊命令")
                                                && message.reply().messageId().equals("help")));
        assertEquals(
                "SENT",
                db.one("select state from outbox where group_id=?", receipt.groupId())
                        .get("state"));
    }

    @Test
    void chatDeliveryKeepsShortCitationsWithoutDiagnosticFooters() throws Exception {
        UUID groupId = group("12345");
        var receipt = db.tx(() -> ingest.ingest(event("12345", "display", "发布安排", true)));
        var context = mock(MemoryService.class);
        when(context.inputLimit()).thenReturn(8000);
        String source = UUID.randomUUID().toString();
        when(context.assemble(any(ContextRequest.class)))
                .thenAnswer(
                        invocation ->
                                new ContextAssembler(
                                                MemoryProperties.defaults(), new ObjectMapper())
                                        .assemble(
                                                invocation.getArgument(0),
                                                "",
                                                List.of(),
                                                List.of(
                                                        new Evidence(
                                                                "message:" + source + ":0",
                                                                SourceKind.RAW,
                                                                "周五发布",
                                                                List.of(UUID.fromString(source)),
                                                                Instant.now(),
                                                                1)),
                                                ""));
        var pi = mock(PiRuntime.class);
        when(pi.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenReturn(
                        new PiRuntime.Result(
                                "## 回复\n**周五发布** [S1]\n[DEBUG] internal log\n<think>private</think>",
                                null));
        var worker = new AgentWorker(db, pi, context, props);
        try {
            worker.tick();
            var run = awaitAgent(receipt.messageId());
            assertEquals("SUCCEEDED", run.get("state"));
            assertEquals("回复\n周五发布 [S1]", run.get("final_text"));
            String body =
                    (String)
                            db.one("select body from outbox where group_id=?", groupId).get("body");
            assertEquals(run.get("final_text"), body);
            assertFalse(body.contains(source));
            verify(pi)
                    .generate(
                            argThat(
                                    request ->
                                            request.timeoutSeconds() > 60
                                                    && request.timeoutSeconds() <= 420),
                            any(GenerationObserver.class));
        } finally {
            worker.close();
        }
    }

    @Test
    void completionFormatsCodeExactlyOnce() {
        group("12345");
        db.tx(() -> ingest.ingest(event("12345", "code", "示例", true)));
        var worker = new AgentWorker(db, mock(PiRuntime.class), memory, props);
        var job = worker.claim().orElseThrow();
        try {
            String code = "    value = '**literal**'";
            worker.complete(job, "```python\n" + code + "\n```", null);
            assertEquals(code, db.one("select final_text from agent_run").get("final_text"));
            assertEquals(code, db.one("select body from outbox").get("body"));
        } finally {
            worker.close();
        }
    }

    @Test
    void queueMayWaitBeyondOldFortyFiveSecondLimitAndGenerationUsesRemainingBudget()
            throws Exception {
        group("12345");
        var receipt = db.tx(() -> ingest.ingest(event("12345", "waiting", "你好", true)));
        db.exec(
                "update agent_run set created_at=? where message_id=?",
                Instant.now().minusSeconds(70),
                receipt.messageId());
        var pi = mock(PiRuntime.class);
        when(pi.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenReturn(new PiRuntime.Result("正常回答", null));
        var worker = new AgentWorker(db, pi, memory, timings(120, 420));
        try {
            worker.tick();
            assertEquals("SUCCEEDED", awaitAgent(receipt.messageId()).get("state"));
            verify(pi)
                    .generate(
                            argThat(
                                    request ->
                                            request.timeoutSeconds() > 300
                                                    && request.timeoutSeconds() <= 350),
                            any(GenerationObserver.class));
        } finally {
            worker.close();
        }
    }

    @Test
    void configurableQueueExpiryDoesNotStartModel() throws Exception {
        group("12345");
        var receipt = db.tx(() -> ingest.ingest(event("12345", "expired", "你好", true)));
        db.exec(
                "update agent_run set created_at=? where message_id=?",
                Instant.now().minusSeconds(2),
                receipt.messageId());
        var pi = mock(PiRuntime.class);
        var worker = new AgentWorker(db, pi, memory, timings(1, 20));
        try {
            worker.tick();
            var run = awaitAgent(receipt.messageId());
            assertEquals("EXPIRED", run.get("state"));
            assertEquals("QUEUE_TIMEOUT", run.get("error_code"));
            verifyNoInteractions(pi);
            assertEquals(1, count("outbox"));
        } finally {
            worker.close();
        }
    }

    @Test
    void totalRequestDeadlineIsIndependentOfQueueLimit() throws Exception {
        group("12345");
        var receipt = db.tx(() -> ingest.ingest(event("12345", "deadline", "你好", true)));
        db.exec(
                "update agent_run set created_at=? where message_id=?",
                Instant.now().minusSeconds(2),
                receipt.messageId());
        var pi = mock(PiRuntime.class);
        var worker = new AgentWorker(db, pi, memory, timings(120, 1));
        try {
            worker.tick();
            var run = awaitAgent(receipt.messageId());
            assertEquals("FAILED", run.get("state"));
            assertEquals("REQUEST_TIMEOUT", run.get("error_code"));
            verifyNoInteractions(pi);
            assertEquals(1, count("outbox"));
        } finally {
            worker.close();
        }
    }

    @Test
    void idleFailureAndDiagnosticsOnlyAnswersNeverPublishPartialContent() throws Exception {
        group("12345");
        var pi = mock(PiRuntime.class);
        when(pi.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenThrow(new IllegalStateException("PI_IDLE_TIMEOUT"));
        var worker = new AgentWorker(db, pi, memory, props);
        try {
            var first = db.tx(() -> ingest.ingest(event("12345", "stall", "你好", true)));
            worker.tick();
            var failed = awaitAgent(first.messageId());
            assertEquals("PI_IDLE_TIMEOUT", failed.get("error_code"));
            doReturn(new PiRuntime.Result("<think>private</think>\n[DEBUG] internal log", null))
                    .when(pi)
                    .generate(any(GenerationRequest.class), any(GenerationObserver.class));
            var second = db.tx(() -> ingest.ingest(event("12345", "empty", "你好", true)));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while ("QUEUED"
                    .equals(
                            db.one(
                                            "select state from agent_run where message_id=?",
                                            second.messageId())
                                    .get("state"))) {
                worker.tick();
                if (System.nanoTime() > deadline) {
                    fail("second run not started");
                }
                Thread.sleep(20);
            }
            assertEquals("EMPTY_ANSWER", awaitAgent(second.messageId()).get("error_code"));
            assertEquals(2, count("outbox"));
            for (var row : db.list("select body from outbox")) {
                assertFalse(((String) row.get("body")).contains("private"));
                assertFalse(((String) row.get("body")).contains("DEBUG"));
            }
        } finally {
            worker.close();
        }
    }

    @Test
    void commandsUseExactPrefixAndNewPreservesMemory() {
        group("12345");
        db.tx(() -> ingest.ingest(event("12345", "a", "/help", false)));
        assertEquals(0, count("agent_run"));
        assertEquals(1, count("outbox"));
        db.tx(() -> ingest.ingest(event("12345", "b", "正文 /new", false)));
        assertEquals(0, count("agent_run"));
        db.tx(() -> ingest.ingest(event("12345", "c", "/memory 安排", false)));
        assertEquals(1, count("agent_run"));
        db.tx(() -> ingest.ingest(event("12345", "d", "/new", false)));
        assertEquals("CANCELLED", db.one("select state from agent_run").get("state"));
        assertEquals(4, count("group_message"));
        db.tx(() -> ingest.ingest(event("12345", "e", "/summary yesterday", false)));
        assertEquals(0, count("daily_run"));
    }

    @Test
    void vectorsArePersistentScopedAndClearInvalidatesRunningResult() {
        UUID a = group("12345"), b = group("23456");
        raw(a, Instant.now(), "群A方案");
        raw(b, Instant.now(), "群B方案");
        assertEquals(2, vectors.indexPending(50));
        assertEquals(2, count("memory_chunk"));
        var found = memory.search(a, "方案", 5);
        assertEquals(1, found.size());
        assertEquals("群A方案", found.get(0).body());
        db.tx(() -> ingest.ingest(event("12345", "q", "/memory 方案", false)));
        var worker = new AgentWorker(db, mock(PiRuntime.class), memory);
        var job = worker.claim().orElseThrow();
        UUID run = job.id();
        db.tx(
                () -> {
                    maintenance.clear(a);
                    return null;
                });
        worker.complete(job, "旧结果", null);
        assertEquals(
                "CANCELLED", db.one("select state from agent_run where id=?", run).get("state"));
        assertTrue(memory.search(a, "方案", 5).isEmpty());
        assertEquals(1, count("memory_chunk"));
        assertEquals(0, count("outbox"));
        worker.close();
    }

    @Test
    void cancellationCannotBeResurrectedByCompletion() {
        group("12345");
        db.tx(() -> ingest.ingest(event("12345", "q", "问题", true)));
        var worker = new AgentWorker(db, mock(PiRuntime.class), memory);
        var job = worker.claim().orElseThrow();
        UUID run = job.id();
        db.tx(() -> ingest.ingest(event("12345", "cancel", "/cancel", false)));
        worker.complete(job, "迟到答案", null);
        assertEquals(
                "CANCELLED", db.one("select state from agent_run where id=?", run).get("state"));
        assertEquals(
                0,
                ((Number)
                                db.one("select count(*) n from outbox where agent_run_id=?", run)
                                        .get("n"))
                        .intValue());
        worker.close();
    }

    @Test
    void yesterdayWindowIsHalfOpenAndSnapshotIsFrozen() {
        UUID a = group("12345");
        raw(a, Instant.parse("2026-11-05T16:00:00Z"), "昨日开始");
        raw(a, Instant.parse("2026-11-06T15:59:59Z"), "昨日结束");
        raw(a, Instant.parse("2026-11-06T16:00:00Z"), "今天");
        UUID id = daily.trigger(a, "WEB");
        assertEquals(id, daily.trigger(a, "COMMAND"));
        var data = daily.claim(id).orElseThrow();
        assertEquals(2, data.sourceIds().size());
        raw(a, Instant.parse("2026-11-06T10:00:00Z"), "迟到文本");
        db.exec("update daily_run set state='QUEUED',attempt=2 where id=?", id);
        var retry = daily.claim(id).orElseThrow();
        assertEquals(data.sourceIds(), retry.sourceIds());
    }

    @Test
    void scheduleFiresOnceAndAdvancesToTomorrow() {
        UUID a = group("12345");
        db.exec(
                "insert into daily_schedule(group_id,enabled,next_run_at) values(?,true,?)",
                a,
                clock.instant());
        daily.scheduleDue();
        daily.scheduleDue();
        assertEquals(1, count("daily_run"));
        assertEquals(
                Instant.parse("2026-11-08T01:00:00Z"),
                ((java.sql.Timestamp)
                                db.one("select next_run_at from daily_schedule").get("next_run_at"))
                        .toInstant());
    }

    @Test
    void rescheduledTaskCanRetryAnEarlierFailureOnTheSameDay() {
        UUID groupId = group("12345"), id = daily.trigger(groupId, "WEB");
        db.exec("update daily_run set state='FAILED',attempt=3 where id=?", id);
        db.exec(
                "insert into daily_schedule(group_id,enabled,next_run_at) values(?,true,?)",
                groupId,
                clock.instant());
        daily.scheduleDue();
        daily.scheduleDue();
        assertEquals(1, count("daily_run"));
        var run = db.one("select state,attempt from daily_run where id=?", id);
        assertEquals("QUEUED", run.get("state"));
        assertEquals(4, ((Number) run.get("attempt")).intValue());
    }

    @Test
    void noDataAndInputLimitDoNotInvokeGeneration() {
        UUID a = group("12345");
        UUID id = daily.trigger(a, "WEB");
        assertTrue(daily.claim(id).isEmpty());
        assertEquals("NO_DATA", db.one("select state from daily_run where id=?", id).get("state"));
        UUID b = group("23456");
        for (int i = 0; i < 1001; i++) {
            raw(b, clock.instant().minus(Duration.ofHours(12)), "消息" + i);
        }
        UUID over = daily.trigger(b, "WEB");
        assertTrue(daily.claim(over).isEmpty());
        assertEquals(
                "LIMIT_EXCEEDED",
                db.one("select state from daily_run where id=?", over).get("state"));
    }

    @Test
    void unknownDeliveryIsNeverRetriedAndBlocksFollowingParts() {
        UUID a = group("12345");
        var sender = mock(MessageTransport.class);
        when(sender.send(any(Message.class)))
                .thenReturn(new Delivery(State.UNKNOWN, null, "TIMEOUT"));
        for (int i = 0; i < 2; i++) {
            db.exec(
                    "insert into outbox(id,group_id,result_key,epoch,part_index,body) values(?,?,'result',0,?,'文本')",
                    UUID.randomUUID(),
                    a,
                    i);
        }
        var worker = new OutboxWorker(db, sender, props);
        worker.tick();
        worker.tick();
        verify(sender, times(1)).send(any(Message.class));
        assertEquals("UNKNOWN", db.one("select state from outbox where part_index=0").get("state"));
        assertEquals(
                "CANCELLED", db.one("select state from outbox where part_index=1").get("state"));
    }

    @Test
    void oldConversationGenerationCannotSend() {
        UUID a = group("12345"), c = UUID.randomUUID();
        db.exec(
                "insert into conversation(id,group_id,sender_id,generation) values(?,?,'u',2)",
                c,
                a);
        db.exec(
                "insert into outbox(id,group_id,result_key,conversation_id,generation,epoch,part_index,body) values(?,?,'result',?,1,0,0,'旧答案')",
                UUID.randomUUID(),
                a,
                c);
        var sender = mock(MessageTransport.class);
        var worker = new OutboxWorker(db, sender, props);
        worker.tick();
        verifyNoInteractions(sender);
        assertEquals("CANCELLED", db.one("select state from outbox").get("state"));
    }

    @Test
    void failedDailyDeliveryShowsItsActualErrorInRunDetails() {
        UUID a = group("12345"), dailyId = daily.trigger(a, "WEB");
        db.exec("update daily_run set state='GENERATED' where id=?", dailyId);
        db.exec(
                "insert into outbox(id,group_id,result_key,daily_run_id,epoch,part_index,body) values(?,?,'daily-delivery',?,0,0,'报告')",
                UUID.randomUUID(),
                a,
                dailyId);
        var sender = mock(MessageTransport.class);
        when(sender.send(any(Message.class)))
                .thenReturn(new Delivery(State.FAILED, null, "QQ_ACTIVE_MESSAGES_DISABLED"));
        new OutboxWorker(db, sender, props).tick();
        var result = db.one("select state,error_code from daily_run where id=?", dailyId);
        assertEquals("DELIVERY_FAILED", result.get("state"));
        assertEquals("QQ_ACTIVE_MESSAGES_DISABLED", result.get("error_code"));
    }

    @Test
    void failedDailyAnalysisCanBeRepeatedWithoutAttemptLimit() {
        UUID groupId = group("12345");
        raw(groupId, clock.instant().minus(Duration.ofHours(12)), "昨日讨论");
        UUID id = daily.trigger(groupId, "WEB");
        var snapshot = daily.claim(id).orElseThrow();
        raw(groupId, clock.instant().minus(Duration.ofHours(12)), "迟到消息");
        for (int attempt = 2; attempt <= 5; attempt++) {
            db.exec(
                    "update daily_run set state='FAILED',error_code='MODEL_FAILED',finished_at=cast(unixepoch('subsec')*1000 as integer) where id=?",
                    id);
            assertEquals(id, daily.trigger(groupId, "WEB"));
            var queued = db.one("select * from daily_run where id=?", id);
            assertEquals("QUEUED", queued.get("state"));
            assertEquals(attempt, ((Number) queued.get("attempt")).intValue());
            assertNull(queued.get("error_code"));
            assertNull(queued.get("finished_at"));
            assertEquals("QUEUED", daily.retry(id).get("state"));
            assertEquals(
                    attempt,
                    ((Number) db.one("select attempt from daily_run where id=?", id).get("attempt"))
                            .intValue());
            assertEquals(snapshot.sourceIds(), daily.claim(id).orElseThrow().sourceIds());
        }
        assertEquals(1, count("daily_run"));
    }

    @Test
    void deliveryRetryReusesReportAndOnlyResendsUnsuccessfulParts() {
        UUID groupId = group("12345"), id = daily.trigger(groupId, "WEB");
        db.exec(
                "update daily_run set state='DELIVERY_FAILED',report='完整报告',error_code='QQ_ACTIVE_MESSAGES_DISABLED' where id=?",
                id);
        for (int part = 0; part < 3; part++) {
            db.exec(
                    "insert into outbox(id,group_id,result_key,daily_run_id,epoch,part_index,body,state,attempts,platform_id,error_code) values(?,?,?,?,0,?,?,?,1,?,?)",
                    UUID.randomUUID(),
                    groupId,
                    "daily:" + id,
                    id,
                    part,
                    "分段" + part,
                    part == 0 ? "SENT" : part == 1 ? "FAILED" : "CANCELLED",
                    part == 0 ? "sent-id" : null,
                    part == 0
                            ? null
                            : part == 1 ? "QQ_ACTIVE_MESSAGES_DISABLED" : "PREVIOUS_PART_FAILED");
        }
        assertEquals(id, daily.trigger(groupId, "WEB"));
        var retry = db.one("select * from daily_run where id=?", id);
        assertEquals("GENERATED", retry.get("state"));
        assertEquals("完整报告", retry.get("report"));
        assertEquals(2, ((Number) retry.get("attempt")).intValue());
        assertNull(retry.get("error_code"));
        var parts = db.list("select * from outbox where daily_run_id=? order by part_index", id);
        assertEquals("SENT", parts.get(0).get("state"));
        assertEquals("sent-id", parts.get(0).get("platform_id"));
        for (var part : parts.subList(1, 3)) {
            assertEquals("PENDING", part.get("state"));
            assertEquals(0, ((Number) part.get("attempts")).intValue());
            assertNull(part.get("error_code"));
        }
        assertEquals("GENERATED", daily.retry(id).get("state"));
        var sender = mock(MessageTransport.class);
        when(sender.send(any(Message.class)))
                .thenReturn(new Delivery(State.ACCEPTED, "new-id", null));
        new OutboxWorker(db, sender, props).tick();
        verify(sender, times(2)).send(any(Message.class));
        assertEquals(
                "DELIVERED", db.one("select state from daily_run where id=?", id).get("state"));
        assertEquals(id, daily.trigger(groupId, "WEB"));
        assertEquals(3, count("outbox"));
        assertEquals(
                2,
                ((Number) db.one("select attempt from daily_run where id=?", id).get("attempt"))
                        .intValue());
    }

    @Test
    void retryProtectsUnknownDeliveryDisabledGroupsAndExpiredSources() {
        UUID groupId = group("12345"), id = daily.trigger(groupId, "WEB");
        db.exec("update daily_run set state='DELIVERY_UNKNOWN' where id=?", id);
        var unknown =
                assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> daily.retry(id));
        assertEquals(409, unknown.getStatusCode().value());
        assertEquals(id, daily.trigger(groupId, "WEB"));
        assertEquals(
                "DELIVERY_UNKNOWN",
                db.one("select state from daily_run where id=?", id).get("state"));
        db.exec("update daily_run set state='FAILED' where id=?", id);
        db.exec("update group_config set reply_enabled=false where id=?", groupId);
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> daily.retry(id));
        db.exec("update group_config set reply_enabled=true,epoch=epoch+1 where id=?", groupId);
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> daily.retry(id));
        assertEquals("FAILED", db.one("select state from daily_run where id=?", id).get("state"));
    }

    @Test
    void deliveryRetryDoesNotResetAnUnknownPart() {
        UUID groupId = group("12345"), id = daily.trigger(groupId, "WEB");
        db.exec("update daily_run set state='DELIVERY_FAILED' where id=?", id);
        for (int part = 0; part < 2; part++) {
            db.exec(
                    "insert into outbox(id,group_id,result_key,daily_run_id,epoch,part_index,body,state) values(?,?,?,?,0,?,'报告',?)",
                    UUID.randomUUID(),
                    groupId,
                    "daily:" + id,
                    id,
                    part,
                    part == 0 ? "FAILED" : "UNKNOWN");
        }
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> daily.retry(id));
        assertEquals(
                "FAILED",
                db.one("select state from outbox where daily_run_id=? and part_index=0", id)
                        .get("state"));
        assertEquals(
                1,
                ((Number) db.one("select attempt from daily_run where id=?", id).get("attempt"))
                        .intValue());
    }

    @Test
    void dailyWorkerProcessesEveryBlockAndPublishesOneReport() throws Exception {
        UUID a = group("12345");
        for (int i = 0; i < 100; i++) {
            raw(a, clock.instant().minus(Duration.ofHours(12)), "关键讨论" + i + "事实".repeat(100));
        }
        UUID id = daily.trigger(a, "WEB");
        var pi = mock(PiRuntime.class);
        when(pi.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenAnswer(
                        invocation -> {
                            GenerationRequest request = invocation.getArgument(0);
                            GenerationObserver observer = invocation.getArgument(1);
                            observer.dispatched();
                            String answer = DailySummaryTestModel.reply(request.userPrompt());
                            if (DailySummaryTestModel.input(request.userPrompt())
                                    .path("action")
                                    .asText()
                                    .equals("WRITE_REPORT")) {
                                var json = new ObjectMapper();
                                var result = json.readTree(answer);
                                ((com.fasterxml.jackson.databind.node.ObjectNode)
                                                result.path("paragraphs").get(0))
                                        .put(
                                                "text",
                                                "## 结论\n**已讨论发布安排**，负责人未明确\n[DEBUG] private runtime log");
                                answer = json.writeValueAsString(result);
                            }
                            return new PiRuntime.Result(answer, null);
                        });
        var worker = new DailySummaryWorker(db, daily, pi);
        worker.tick();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while ("QUEUED"
                            .equals(
                                    db.one("select state from daily_run where id=?", id)
                                            .get("state"))
                    || "RUNNING"
                            .equals(
                                    db.one("select state from daily_run where id=?", id)
                                            .get("state"))) {
                if (System.nanoTime() > deadline) {
                    fail("daily worker timed out");
                }
                Thread.sleep(20);
            }
            var run = db.one("select * from daily_run where id=?", id);
            assertEquals("GENERATED", run.get("state"));
            assertEquals(100, ((List<?>) run.get("source_ids")).size());
            verify(pi, atLeast(2))
                    .generate(
                            argThat(
                                    request ->
                                            request.timeoutSeconds() > 60
                                                    && dev.qqbot.model.TokenEstimator.estimateInput(
                                                                    request.systemPrompt(),
                                                                    request.userPrompt())
                                                            <= request.maxInputTokens()),
                            any(GenerationObserver.class));
            assertTrue(count("outbox") > 0);
            String report = (String) run.get("report");
            assertTrue(report.contains("["));
            assertFalse(report.contains("DEBUG"));
            assertFalse(report.contains("**"));
            String display =
                    String.join(
                            "",
                            db
                                    .list(
                                            "select body from outbox where daily_run_id=? order by part_index",
                                            id)
                                    .stream()
                                    .map(part -> (String) part.get("body"))
                                    .toList());
            assertTrue(display.contains("负责人未明确"));
            assertFalse(display.contains("完整报告与来源可在管理端查看"));
            assertFalse(display.matches("(?s).*\\[[0-9a-f-]{36}\\].*"));
            assertFalse(display.contains("DEBUG"));
            assertFalse(display.contains("##"));
        } finally {
            worker.close();
        }
    }

    @Test
    void dailyWorkerRejectsUnknownMissingAndDiagnosticsOnlyCitations() throws Exception {
        for (String kind : List.of("unknown", "missing", "diagnostic")) {
            UUID groupId = group("invalid-" + kind);
            raw(groupId, clock.instant().minus(Duration.ofHours(12)), "昨日事实");
            UUID id = daily.trigger(groupId, "WEB");
            var pi = mock(PiRuntime.class);
            when(pi.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                    .thenAnswer(
                            invocation -> {
                                GenerationRequest request = invocation.getArgument(0);
                                GenerationObserver observer = invocation.getArgument(1);
                                observer.dispatched();
                                String answer = DailySummaryTestModel.reply(request.userPrompt());
                                if (DailySummaryTestModel.input(request.userPrompt())
                                        .path("action")
                                        .asText()
                                        .equals("WRITE_REPORT")) {
                                    var json = new ObjectMapper();
                                    var result = json.readTree(answer);
                                    var paragraph =
                                            (com.fasterxml.jackson.databind.node.ObjectNode)
                                                    result.path("paragraphs").get(0);
                                    if (kind.equals("unknown")) {
                                        paragraph
                                                .putArray("sources")
                                                .add(UUID.randomUUID().toString());
                                    } else if (kind.equals("missing")) {
                                        paragraph.putArray("sources");
                                    } else {
                                        paragraph.put(
                                                "text",
                                                "[INFO] internal ["
                                                        + paragraph.path("sources").get(0).asText()
                                                        + "]");
                                    }
                                    answer = json.writeValueAsString(result);
                                }
                                return new PiRuntime.Result(answer, null);
                            });
            var worker = new DailySummaryWorker(db, daily, pi);
            try {
                worker.tick();
                long deadline =
                        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (Set.of("QUEUED", "RUNNING")
                        .contains(
                                db.one("select state from daily_run where id=?", id)
                                        .get("state"))) {
                    if (System.nanoTime() > deadline) {
                        fail("daily rejection timed out");
                    }
                    Thread.sleep(20);
                }
                var run = db.one("select state,error_code from daily_run where id=?", id);
                assertEquals("FAILED", run.get("state"));
                assertEquals(
                        kind.equals("unknown")
                                ? "INVALID_CITATION"
                                : kind.equals("missing") ? "MISSING_CITATION" : "EMPTY_REPORT",
                        run.get("error_code"));
                assertEquals(0, count("outbox"));
            } finally {
                worker.close();
            }
        }
    }

    @Test
    void retentionErasesExpiredSourcesReportsAndTemporaryContext() {
        UUID a = group("12345"), old = UUID.randomUUID();
        db.exec(
                """
                insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,epoch,kind)
                values(?,?,'old','u','u','expired',?,0,'TEXT')
                """,
                old,
                a,
                clock.instant().minus(Duration.ofDays(31)));
        raw(a, clock.instant(), "fresh");
        UUID id = daily.trigger(a, "WEB");
        db.exec(
                "update daily_run set state='DELIVERED',report='expired report',source_ids=? where id=?",
                db.json(List.of(old.toString())),
                id);
        db.exec(
                "insert into conversation(id,group_id,sender_id,checkpoint_ref) values(?,?,'u','invalid-old-checkpoint')",
                UUID.randomUUID(),
                a);
        new MemoryMaintenance(
                        db,
                        props,
                        archive(),
                        new MemorySearchIndex(db, new MarkdownMemory(db, props), clock),
                        clock,
                        new RetentionProperties(30, 30, 30))
                .cleanup();
        assertEquals(1, count("group_message"));
        assertEquals("fresh", db.one("select body from group_message").get("body"));
        assertNull(db.one("select report from daily_run where id=?", id).get("report"));
        assertNull(db.one("select checkpoint_ref from conversation").get("checkpoint_ref"));
    }
}
