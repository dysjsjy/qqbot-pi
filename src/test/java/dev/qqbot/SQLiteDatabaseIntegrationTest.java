/**
 * [INPUT]: 使用临时 SQLite 执行群入站、归档与日报认领。
 * [OUTPUT]: 验证生产数据库接线以及核心读写契约。
 * [POS]: SQLite 集成测试，覆盖真实迁移和持久化服务。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.memory.*;
import dev.qqbot.message.*;
import dev.qqbot.persistence.Db;
import dev.qqbot.pi.PiRuntime;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

class SQLiteDatabaseIntegrationTest extends DatabaseBehaviorTest {
    @TempDir static Path directory;

    @BeforeAll
    static void database() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:sqlite:"
                                + directory.resolve("bot.db")
                                + "?foreign_keys=on&busy_timeout=10000&transaction_mode=IMMEDIATE");
        Flyway.configure().dataSource(source).locations("classpath:db/sqlite").load().migrate();
        db =
                new Db(
                        new JdbcTemplate(source),
                        new TransactionTemplate(new DataSourceTransactionManager(source)),
                        new ObjectMapper().findAndRegisterModules());
        var gateway = new BotProperties.Gateway(true, "http://localhost", "token", "10001", "");
        props =
                new BotProperties(
                        "admin",
                        "",
                        directory,
                        false,
                        new BotProperties.Pi(
                                "node", "fixture", "mock", "mock", "TEST_KEY", "", 60, ""),
                        new BotProperties.Embedding("http://localhost", "", "test", 3, 0.5),
                        new BotProperties.Qq(
                                gateway.enabled(),
                                new BotProperties.OfficialQq(
                                        gateway.accountId(),
                                        "",
                                        "https://api.bot.qq.com",
                                        "https://api.bot.qq.com/app/getAppAccessToken",
                                        false)),
                        gateway,
                        new BotProperties.Rag(true));
    }

    @Test
    void upgradingV5PreservesExistingGroupAndMessageAsGroupScope() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:sqlite:"
                                + directory.resolve("upgrade-private.db")
                                + "?foreign_keys=on");
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/sqlite")
                .target("5")
                .load()
                .migrate();
        var jdbc = new JdbcTemplate(source);
        String groupId = UUID.randomUUID().toString(), messageId = UUID.randomUUID().toString();
        jdbc.update(
                "insert into group_config(id,channel,account_id,external_id,name,enabled) values(?,'QQ','10001','12345','原群',true)",
                groupId);
        jdbc.update(
                "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,epoch,kind) values(?,?,'old-key','user','成员','原消息',1000,0,'TEXT')",
                messageId,
                groupId);
        Flyway.configure().dataSource(source).locations("classpath:db/sqlite").load().migrate();
        var scope =
                jdbc.queryForMap(
                        "select chat_type,target_id,external_id,enabled from group_config where id=?",
                        groupId);
        assertEquals("GROUP", scope.get("chat_type"));
        assertNull(scope.get("target_id"));
        assertEquals("12345", scope.get("external_id"));
        assertEquals(1, ((Number) scope.get("enabled")).intValue());
        assertEquals(
                "原消息",
                jdbc.queryForObject(
                        "select body from group_message where id=?", String.class, messageId));
    }

    @Test
    void noRagPreservesMessagesAndChatWithoutEmbeddingOrSourceInjection() throws Exception {
        var noRag =
                new BotProperties(
                        props.adminUsername(),
                        props.adminPasswordHash(),
                        directory,
                        false,
                        props.pi(),
                        new BotProperties.Embedding("", "", "", 0, 0),
                        props.qq(),
                        props.weixin(),
                        new BotProperties.Rag(false));
        var embedding = mock(EmbeddingClient.class);
        var localMemory = TestServices.memory(db, embedding, noRag);
        var localIngest = TestServices.ingest(db, daily, noRag);
        UUID id = group("12345");
        db.tx(() -> localIngest.ingest(event("12345", "ordinary", "普通群消息", false)));
        db.tx(() -> localIngest.ingest(event("12345", "query", "只聊天", true)));
        assertEquals(2, count("group_message"));
        assertEquals(1, count("agent_run"));
        assertEquals(0, new VectorMemory(db, embedding, noRag).indexPending(20));
        assertTrue(localMemory.search(id, "问题", 5).isEmpty());
        assertEquals(
                0,
                ((Number)
                                db.one(
                                                "select count(*) n from group_message where index_state='PENDING'")
                                        .get("n"))
                        .intValue());
        var pi = mock(PiRuntime.class);
        when(pi.generate(
                        any(dev.qqbot.model.GenerationRequest.class),
                        any(dev.qqbot.model.GenerationObserver.class)))
                .thenReturn(new PiRuntime.Result("正常回答 [S1]", null));
        var worker = new AgentWorker(db, pi, localMemory);
        try {
            worker.tick();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!"SUCCEEDED".equals(db.one("select state from agent_run").get("state"))) {
                if (System.nanoTime() > deadline) {
                    fail("chat timed out");
                }
                Thread.sleep(20);
            }
            verify(pi)
                    .generate(
                            argThat(
                                    request ->
                                            request.userPrompt().contains("只聊天")
                                                    && request.userPrompt().contains("普通群消息")
                                                    && request.userPrompt().contains("历史消息")),
                            any(dev.qqbot.model.GenerationObserver.class));
            assertEquals("正常回答 [S1]", db.one("select final_text from agent_run").get("final_text"));
        } finally {
            worker.close();
        }
        db.tx(() -> localIngest.ingest(event("12345", "memory", "/memory 查记录", false)));
        assertEquals(2, count("agent_run"));
        assertEquals(
                "MEMORY", db.one("select kind from agent_run where state='QUEUED'").get("kind"));
        raw(id, clock.instant().minus(Duration.ofHours(12)), "昨日消息");
        assertTrue(daily.claim(daily.trigger(id, "WEB")).isPresent());
        verifyNoInteractions(embedding);
    }

    @Test
    void localVectorsRankByCosineAndExcludeCurrentMessage() {
        UUID id = group("12345");
        raw(id, Instant.now(), "匹配");
        raw(id, Instant.now(), "不匹配");
        when(embeddings.embed(anyList()))
                .thenAnswer(
                        i ->
                                ((List<String>) i.getArgument(0))
                                        .stream()
                                                .map(
                                                        text ->
                                                                text.equals("不匹配")
                                                                        ? new float[] {0, 1, 0}
                                                                        : new float[] {1, 0, 0})
                                                .toList());
        assertEquals(2, vectors.indexPending(20));
        var found = memory.search(id, "问题", 5);
        assertEquals(1, found.size());
        assertEquals("匹配", found.get(0).body());
        assertTrue(memory.search(id, "问题", 5, UUID.fromString(found.get(0).id())).isEmpty());
    }

    @Test
    void markdownIsIncludedInChatWithoutRag() throws Exception {
        var noRag =
                new BotProperties(
                        props.adminUsername(),
                        props.adminPasswordHash(),
                        directory,
                        false,
                        props.pi(),
                        props.embedding(),
                        props.qq(),
                        props.weixin(),
                        new BotProperties.Rag(false));
        UUID id = group("12345");
        new MarkdownMemory(db, noRag).write(id, "# 项目背景\n使用中文回复");
        var localMemory = TestServices.memory(db, mock(EmbeddingClient.class), noRag);
        var localIngest = TestServices.ingest(db, daily, noRag);
        db.tx(() -> localIngest.ingest(event("12345", "q", "你好", true)));
        var pi = mock(PiRuntime.class);
        when(pi.generate(
                        any(dev.qqbot.model.GenerationRequest.class),
                        any(dev.qqbot.model.GenerationObserver.class)))
                .thenReturn(new PiRuntime.Result("你好", null));
        var worker = new AgentWorker(db, pi, localMemory);
        try {
            worker.tick();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!"SUCCEEDED".equals(db.one("select state from agent_run").get("state"))) {
                if (System.nanoTime() > deadline) {
                    fail("chat timed out");
                }
                Thread.sleep(20);
            }
            verify(pi)
                    .generate(
                            argThat(
                                    request ->
                                            request.userPrompt().contains("你好")
                                                    && request.userPrompt()
                                                            .contains("# 项目背景\n使用中文回复")),
                            any(dev.qqbot.model.GenerationObserver.class));
        } finally {
            worker.close();
        }
    }

    @Test
    void repliesRebuildContextInsteadOfRestoringUnboundedCheckpoints() throws Exception {
        var noRag =
                new BotProperties(
                        props.adminUsername(),
                        props.adminPasswordHash(),
                        directory,
                        false,
                        props.pi(),
                        props.embedding(),
                        props.qq(),
                        props.weixin(),
                        new BotProperties.Rag(false));
        UUID id = group("12345");
        var localIngest = TestServices.ingest(db, daily, noRag);
        db.tx(() -> localIngest.ingest(event("12345", "fresh", "当前问题", true)));
        db.exec(
                "update conversation set checkpoint_ref='old-unbounded-checkpoint' where group_id=?",
                id);
        var pi = mock(PiRuntime.class);
        when(pi.generate(
                        any(dev.qqbot.model.GenerationRequest.class),
                        any(dev.qqbot.model.GenerationObserver.class)))
                .thenReturn(new PiRuntime.Result("新回答", null));
        var worker =
                new AgentWorker(
                        db, pi, TestServices.memory(db, mock(EmbeddingClient.class), noRag));
        try {
            worker.tick();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!"SUCCEEDED".equals(db.one("select state from agent_run").get("state"))) {
                if (System.nanoTime() > deadline) {
                    fail("reply timed out");
                }
                Thread.sleep(20);
            }
            verify(pi)
                    .generate(
                            argThat(request -> request.userPrompt().endsWith("当前问题")),
                            any(dev.qqbot.model.GenerationObserver.class));
            assertNull(
                    db.one("select checkpoint_ref from conversation where group_id=?", id)
                            .get("checkpoint_ref"));
        } finally {
            worker.close();
        }
    }
}
