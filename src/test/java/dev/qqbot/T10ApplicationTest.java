/**
 * [INPUT]: 依赖临时 SQLite、旧版文件夹具与完整 Spring 应用。
 * [OUTPUT]: 验证记忆计量、V6 升级、重建和重启的数据一致性。
 * [POS]: 记忆升级回归；旧夹具不调用需要新表的文件服务。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.qqbot.memory.*;
import dev.qqbot.message.*;
import dev.qqbot.model.*;
import dev.qqbot.persistence.Db;
import dev.qqbot.pi.PiRuntime;
import dev.qqbot.pi.PiSession;
import dev.qqbot.schedule.*;
import dev.qqbot.usage.MeteredTextGenerator;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class T10ApplicationTest {
    private static final Instant NOW = Instant.parse("2026-11-11T04:00:00Z");
    private static final ModelUsage USAGE =
            new ModelUsage(100L, 10L, 20L, 0L, 5L, UsageStatus.REPORTED);
    @TempDir Path directory;

    MemoryV2TestDatabase database(String target) {
        return new MemoryV2TestDatabase(directory.resolve("application.db"), target);
    }

    @Test
    void L05FullSpringWiresMeteringIntoChatV1V2AndDailyWorkers() throws Exception {
        try (var fixture = database(null)) {
            var calls = new CopyOnWriteArrayList<GenerationRequest>();
            var delegate = delegate(fixture.db, calls);
            try (var application = T10StartupTest.start(fixture, directory, delegate)) {
                assertInstanceOf(
                        MeteredTextGenerator.class, application.getBean(TextGenerator.class));
                assertSame(delegate, application.getBean("piTextGenerator"));
                assertEquals(2, application.getBeansOfType(TextGenerator.class).size());
                var files = application.getBean(MarkdownMemory.class);
                var policies = application.getBean(MemoryPolicyService.class);
                var chat =
                        new MemoryV2FixtureLoader(fixture.db, MemoryV2FixtureLoader.cases().get(0))
                                .load(files);
                await(
                        () -> {
                            application.getBean(AgentWorker.class).tick();
                            return fixture.db.one(
                                    "select * from agent_run where id=?", chat.runId());
                        },
                        "SUCCEEDED");
                assertEquals(1, calls.size());
                assertEquals(Purpose.CHAT, calls.get(0).purpose());
                UUID legacy = fixture.group("spring-v1");
                policies.updateSettings(legacy, 0, "SUMMARY", 1, 6000, 30);
                for (int number = 1; number <= 100; number++) {
                    fixture.message(
                            legacy,
                            "v1-" + number,
                            "A",
                            "发布事项 " + number,
                            number,
                            NOW.minusSeconds(300 - number));
                }
                Map<String, Object> first =
                        await(
                                () -> {
                                    application.getBean(MarkdownMemoryWorker.class).tick();
                                    return fixture.db.one(
                                            "select * from memory_document where group_id=? and kind='SUMMARY'",
                                            legacy);
                                },
                                "READY");
                assertEquals(1, ((Number) first.get("strategy_version")).intValue());
                assertEquals(100, ((Number) first.get("source_count")).intValue());
                var modern =
                        new MemoryV2FixtureLoader(fixture.db, MemoryV2FixtureLoader.cases().get(1))
                                .load(files);
                UUID modernGroup = modern.request().scope().groupId();
                fixture.db.exec(
                        "update agent_run set state='CANCELLED' where id=?", modern.runId());
                fixture.message(
                        modernGroup,
                        "v2-1",
                        "A",
                        "v2-source-a " + "a".repeat(600),
                        5,
                        NOW.minusSeconds(120));
                fixture.message(
                        modernGroup,
                        "v2-2",
                        "B",
                        "v2-source-b " + "b".repeat(600),
                        6,
                        NOW.minusSeconds(100));
                var settings = policies.settings(modernGroup);
                policies.updateSettings(
                        modernGroup,
                        ((Number) settings.get("revision")).longValue(),
                        "SUMMARY",
                        2,
                        1024,
                        5);
                Map<String, Object> second =
                        await(
                                () -> {
                                    application.getBean(MarkdownMemoryWorker.class).tick();
                                    return fixture.db.one(
                                            "select * from memory_document where group_id=? and kind='SUMMARY'",
                                            modernGroup);
                                },
                                "READY");
                assertEquals(2, ((Number) second.get("strategy_version")).intValue());
                assertEquals(
                        1,
                        calls.stream()
                                .filter(call -> call.runId().equals(second.get("id")))
                                .count());
                UUID dailyGroup = fixture.group("spring-daily");
                fixture.message(
                        dailyGroup,
                        "daily-source",
                        "A",
                        "昨日明确的发布安排",
                        1,
                        NOW.minus(Duration.ofHours(18)));
                UUID daily =
                        application.getBean(DailySummaryService.class).trigger(dailyGroup, "WEB");
                await(
                        () -> {
                            application.getBean(DailySummaryWorker.class).tick();
                            return fixture.db.one("select * from daily_run where id=?", daily);
                        },
                        "GENERATED");
                long dailyCalls =
                        calls.stream()
                                .filter(
                                        call ->
                                                call.runId().equals(daily)
                                                        && call.purpose() == Purpose.DAILY_SUMMARY)
                                .count();
                assertTrue(dailyCalls >= 3 && dailyCalls <= 8);
                assertFalse(
                        fixture.db
                                .list("select id from outbox where daily_run_id=?", daily)
                                .isEmpty());
                assertTrue(
                        calls.stream()
                                .anyMatch(
                                        call ->
                                                call.runId().equals(first.get("id"))
                                                        && call.purpose()
                                                                == Purpose.MEMORY_SUMMARY));
                assertEquals(
                        calls.size(),
                        new HashSet<>(calls.stream().map(GenerationRequest::callId).toList())
                                .size());
                var ledger = fixture.db.list("select * from model_call");
                assertEquals(calls.size(), ledger.size());
                for (var call : calls) {
                    var row = fixture.db.one("select * from model_call where id=?", call.callId());
                    assertEquals(call.runId(), row.get("run_id"));
                    assertEquals(call.groupId(), row.get("group_id"));
                    assertEquals(call.purpose().name(), row.get("purpose"));
                    assertEquals("SUCCEEDED", row.get("state"));
                    assertEquals(true, row.get("dispatched"));
                    assertEquals("REPORTED", row.get("usage_status"));
                    assertTrue(
                            TokenEstimator.estimateInput(call.systemPrompt(), call.userPrompt())
                                    <= call.maxInputTokens());
                }
                verify(delegate, never())
                        .generateNative(any(UUID.class), anyString(), any(PiSession.class));
                verify(delegate, never())
                        .generateNative(
                                any(UUID.class), anyString(), any(PiSession.class), anyInt());
            }
        }
    }

    @Test
    void L08SameV6SnapshotSurvivesUpgradeRebuildAndRestart() throws Exception {
        try (var fixture = database("6")) {
            UUID group = fixture.group("upgrade-summary"),
                    rawGroup = fixture.group("upgrade-raw"),
                    first = UUID.randomUUID(),
                    second = UUID.randomUUID(),
                    outside = UUID.randomUUID(),
                    summary = UUID.randomUUID(),
                    raw = UUID.randomUUID();
            for (UUID id : List.of(first, second, outside)) {
                fixture.db.exec(
                        "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,received_at,epoch,kind) values(?,?,?,'A','甲',?,?,?,2,'TEXT')",
                        id,
                        id.equals(outside) ? rawGroup : group,
                        id.toString(),
                        id.equals(outside)
                                ? "FORBIDDEN_OTHER_GROUP"
                                : "升级资料 " + (id.equals(first) ? "先备份" : "再迁移"),
                        NOW.minusSeconds(id.equals(first) ? 120 : 110),
                        NOW.minusSeconds(id.equals(first) ? 120 : 110));
            }
            fixture.db.exec("insert into memory_policy(group_id,mode) values(?,'SUMMARY')", group);
            fixture.db.exec("insert into memory_policy(group_id,mode) values(?,'RAW')", rawGroup);
            fixture.db.exec(
                    "insert into memory_document(id,group_id,epoch,kind,state,source_count,created_at,finished_at) values(?,?,2,'SUMMARY','READY',2,?,?)",
                    summary,
                    group,
                    NOW.minusSeconds(60),
                    NOW.minusSeconds(60));
            fixture.db.exec(
                    "insert into memory_document(id,group_id,epoch,kind,state,source_count,created_at,finished_at) values(?,?,2,'RAW','READY',1,?,?)",
                    raw,
                    rawGroup,
                    NOW.minusSeconds(60),
                    NOW.minusSeconds(60));
            for (UUID source : List.of(first, second)) {
                fixture.db.exec(
                        "insert into memory_document_source(document_id,message_id,kind) values(?,?,'SUMMARY')",
                        summary,
                        source);
            }
            fixture.db.exec(
                    "insert into memory_document_source(document_id,message_id,kind) values(?,?,'RAW')",
                    raw,
                    outside);
            var properties = properties();
            var files = new MarkdownMemory(fixture.db, properties);
            // ========== 直接构造 V6 文件快照；新服务需要 V10 墓碑表 ==========
            Path legacyRoot = properties.stateDir().resolve("memory");
            legacyFile(legacyRoot.resolve(group.toString()).resolve("MEMORY.md"), "人工升级说明");
            legacyFile(
                    legacyRoot
                            .resolve(group.toString())
                            .resolve("summaries")
                            .resolve(summary + ".md"),
                    "旧概括：升级资料先备份");
            legacyFile(
                    legacyRoot.resolve(rawGroup.toString()).resolve("raw").resolve(raw + ".md"),
                    "旧RAW完整正文");
            assertNull(
                    fixture.db.one(
                            "select name from sqlite_master where type='table' and name='memory_file_tombstone'"));
            var original =
                    fixture.db.list("select id,group_id,body,epoch from group_message order by id");
            String summaryHash = MemberLabels.hash("旧概括：升级资料先备份"),
                    rawHash = MemberLabels.hash("旧RAW完整正文");
            var migrations =
                    Flyway.configure()
                            .dataSource(fixture.source)
                            .locations("classpath:db/sqlite")
                            .load();
            migrations.migrate();
            migrations.validate();
            for (UUID scoped : List.of(group, rawGroup)) {
                var settings = new MemoryPolicyService(fixture.db).settings(scoped);
                assertFalse(settings.containsKey("contextMode"));
                assertEquals(1, settings.get("strategyVersion"));
            }
            assertTrue(fixture.db.list("select id from model_call").isEmpty());
            var calls = new CopyOnWriteArrayList<GenerationRequest>();
            var delegate = delegate(fixture.db, calls);
            try (var application = T10StartupTest.start(fixture, directory, delegate)) {
                var index = application.getBean(MemorySearchIndex.class);
                var worker = application.getBean(MemorySearchWorker.class);
                fixture.message(
                        group, "future", "B", "FORBIDDEN_FUTURE", 100, NOW.minusSeconds(180));
                worker.tick();
                var scope = new MemoryScope(group, 2, null, 20, NOW, NOW);
                var before = index.lexical(scope, "升级资料", QueryMode.HISTORY, 200).evidence();
                assertFalse(before.isEmpty());
                index.rebuild(group);
                assertTrue(
                        fixture.db
                                .list("select id from memory_search_chunk where group_id=?", group)
                                .isEmpty());
                worker.tick();
                assertEquals(
                        before, index.lexical(scope, "升级资料", QueryMode.HISTORY, 200).evidence());
                assertTrue(calls.isEmpty());
                UUID run = queued(fixture, group, "继续验证", 20);
                await(
                        () -> {
                            application.getBean(AgentWorker.class).tick();
                            return fixture.db.one("select * from agent_run where id=?", run);
                        },
                        "SUCCEEDED");
                String prompt = calls.get(0).userPrompt();
                assertTrue(prompt.contains("升级资料"));
                assertTrue(prompt.contains("旧概括"));
                assertFalse(prompt.contains("FORBIDDEN_"));
            }
            assertEquals(1, calls.size());
            try (var application = T10StartupTest.start(fixture, directory, delegate)) {
                application.getBean(MemorySearchWorker.class).tick();
                var archive = application.getBean(MarkdownArchive.class);
                archive.prepare();
                for (int count = 0; count < 20; count++) {
                    var job = archive.claim();
                    if (job == null) {
                        break;
                    }
                    archive.process(job, application.getBean(TextGenerator.class));
                }
                assertEquals(1, calls.size());
                assertEquals(1, fixture.db.list("select id from model_call").size());
                assertEquals(
                        "SUMMARY",
                        application.getBean(MemoryPolicyService.class).settings(group).get("mode"));
                for (var row : original) {
                    assertEquals(
                            row,
                            fixture.db.one(
                                    "select id,group_id,body,epoch from group_message where id=?",
                                    row.get("id")));
                }
                assertEquals(
                        2,
                        fixture.db
                                .list(
                                        "select message_id from memory_document_source where document_id=?",
                                        summary)
                                .size());
                assertEquals(
                        1,
                        fixture.db
                                .list(
                                        "select message_id from memory_document_source where document_id=?",
                                        raw)
                                .size());
                assertEquals(
                        summaryHash,
                        MemberLabels.hash(files.readDocument(group, summary, "SUMMARY")));
                assertEquals(rawHash, MemberLabels.hash(files.readDocument(rawGroup, raw, "RAW")));
                assertEquals("人工升级说明", files.read(group));
                for (UUID document : List.of(summary, raw)) {
                    var row =
                            fixture.db.one(
                                    "select state,strategy_version from memory_document where id=?",
                                    document);
                    assertEquals("READY", row.get("state"));
                    assertEquals(1, ((Number) row.get("strategy_version")).intValue());
                }
            }
        }
    }

    private BotProperties properties() {
        var gateway = new BotProperties.Gateway(false, "", "", "test", "");
        return new BotProperties(
                "admin",
                "",
                directory.resolve("state"),
                false,
                new BotProperties.Pi("node", "unused", "test", "test", "KEY", "", 5, ""),
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

    private static void legacyFile(Path file, String content) throws java.io.IOException {
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, content, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static PiRuntime delegate(Db db, List<GenerationRequest> calls) {
        var delegate = mock(PiRuntime.class);
        when(delegate.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenAnswer(
                        invocation -> {
                            GenerationRequest request = invocation.getArgument(0);
                            GenerationObserver observer = invocation.getArgument(1);
                            calls.add(request);
                            observer.dispatched();
                            observer.usage(USAGE);
                            String answer = "合成正常答复";
                            if (request.purpose() == Purpose.MEMORY_SUMMARY) {
                                var document =
                                        db.one(
                                                "select strategy_version from memory_document where id=?",
                                                request.runId());
                                if (((Number) document.get("strategy_version")).intValue() == 2) {
                                    var body =
                                            new StringBuilder(
                                                    "## 话题\n无\n## 已明确事项\n无\n## 待办\n无\n## 未决与纠正\n无\n## 来源\n");
                                    for (var source :
                                            db.list(
                                                    "select message_id from memory_document_source where document_id=? order by message_id",
                                                    request.runId())) {
                                        body.append("- [")
                                                .append(source.get("message_id"))
                                                .append("]\n");
                                    }
                                    answer = body.toString();
                                } else {
                                    answer = "合成分批摘要";
                                }
                            } else if (request.purpose() == Purpose.DAILY_SUMMARY) {
                                answer = DailySummaryTestModel.reply(request.userPrompt());
                            }
                            return new TextGenerator.Result(answer, null, USAGE);
                        });
        return delegate;
    }

    private static Map<String, Object> await(Supplier<Map<String, Object>> read, String expected)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        for (; ; ) {
            var row = read.get();
            if (row != null && !Set.of("QUEUED", "RUNNING").contains(row.get("state"))) {
                assertEquals(expected, row.get("state"), row.toString());
                return row;
            }
            if (System.nanoTime() > deadline) {
                fail("Application workflow did not reach " + expected);
            }
            Thread.sleep(10);
        }
    }

    private static UUID queued(
            MemoryV2TestDatabase fixture, UUID group, String question, long sequence) {
        UUID message =
                fixture.message(
                        group,
                        "question-" + sequence,
                        "A",
                        question,
                        sequence,
                        NOW.minusSeconds(30));
        var conversation = new ConversationService(fixture.db).ensure(group, "A");
        UUID run = UUID.randomUUID();
        fixture.db.exec(
                "insert into agent_run(id,group_id,message_id,conversation_id,generation,epoch,kind,prompt,state,context_seq,context_occurred_at,created_at) values(?,?,?,?,?,2,'CHAT',?,'QUEUED',?,?,?)",
                run,
                group,
                message,
                conversation.id(),
                conversation.generation(),
                question,
                sequence,
                NOW.minusSeconds(30),
                NOW);
        return run;
    }
}
