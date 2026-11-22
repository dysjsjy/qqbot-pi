package dev.qqbot.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.BotProperties;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

class SQLiteLifecycleTest {
    @TempDir Path directory;
    DriverManagerDataSource source;
    Db db;
    BotProperties props;

    @BeforeEach
    void database() {
        source =
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
        var gateway = new BotProperties.Gateway(false, "", "", "", "");
        props =
                new BotProperties(
                        "admin",
                        "",
                        directory,
                        false,
                        new BotProperties.Pi(
                                "node", "fixture", "mock", "mock", "TEST_KEY", "", 60, ""),
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
    }

    @Test
    void locksTheDatabaseAcrossDifferentStateDirectoriesAndReleasesAfterShutdown()
            throws Exception {
        var first = new Lifecycle(source, db, props);
        first.start();
        try {
            var other =
                    new BotProperties(
                            "admin",
                            "",
                            directory.resolve("different-state"),
                            false,
                            props.pi(),
                            props.embedding(),
                            props.qq(),
                            props.weixin(),
                            props.rag());
            assertThrows(
                    IllegalStateException.class, () -> new Lifecycle(source, db, other).start());
            assertThrows(
                    IllegalStateException.class, () -> new Lifecycle(source, db, props).start());
        } finally {
            first.close();
        }
        var restarted = new Lifecycle(source, db, props);
        try {
            restarted.start();
        } finally {
            restarted.close();
        }
    }

    @Test
    void restartMarksInFlightWorkAndPreservesEmbeddingIdentityWhenRagIsOff() throws Exception {
        UUID group = UUID.randomUUID(), conversation = UUID.randomUUID();
        db.exec(
                "insert into group_config(id,channel,account_id,external_id,name,enabled) values(?,'QQ','bot','12345','test',true)",
                group);
        db.exec(
                "insert into conversation(id,group_id,sender_id) values(?,?,'user')",
                conversation,
                group);
        db.exec(
                "insert into agent_run(id,group_id,conversation_id,generation,epoch,kind,prompt,state) values(?,?,?,0,0,'CHAT','test','RUNNING')",
                UUID.randomUUID(),
                group,
                conversation);
        db.exec(
                "insert into daily_run(id,group_id,business_date,epoch,trigger_type,state) values(?,?,'2026-11-07',0,'WEB','RUNNING')",
                UUID.randomUUID(),
                group);
        db.exec(
                "insert into outbox(id,group_id,result_key,epoch,part_index,body,state) values(?,?,'test',0,0,'test','SENDING')",
                UUID.randomUUID(),
                group);
        db.exec(
                "insert into app_metadata(name,value) values('embedding_identity','existing-model:3')");
        db.exec(
                "insert into memory_document(id,group_id,epoch,kind,state,source_count) values(?,?,0,'SUMMARY','RUNNING',100)",
                UUID.randomUUID(),
                group);
        var lifecycle = new Lifecycle(source, db, props);
        try {
            lifecycle.start();
            assertEquals("INTERRUPTED", db.one("select state from agent_run").get("state"));
            assertEquals("INTERRUPTED", db.one("select state from daily_run").get("state"));
            assertEquals("UNKNOWN", db.one("select state from outbox").get("state"));
            assertEquals("QUEUED", db.one("select state from memory_document").get("state"));
            assertEquals(
                    2,
                    ((Number) db.one("select attempt from memory_document").get("attempt"))
                            .intValue());
            assertEquals(
                    "SERVICE_RESTART_UNVERIFIED",
                    db.one("select reason from collection_gap").get("reason"));
            assertEquals("existing-model:3", db.one("select value from app_metadata").get("value"));
        } finally {
            lifecycle.close();
        }
    }

    @Test
    void ragStillRequiresEmbeddingConfiguration() {
        var rag =
                new BotProperties(
                        "admin",
                        "",
                        directory,
                        false,
                        props.pi(),
                        props.embedding(),
                        props.qq(),
                        props.weixin(),
                        new BotProperties.Rag(true));
        assertThrows(IllegalStateException.class, () -> new Lifecycle(source, db, rag).start());
    }
}
