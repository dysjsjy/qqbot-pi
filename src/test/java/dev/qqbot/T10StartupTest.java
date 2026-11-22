package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import dev.qqbot.memory.*;
import dev.qqbot.model.*;
import dev.qqbot.persistence.Db;
import dev.qqbot.pi.PiRuntime;
import dev.qqbot.usage.ModelCallStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;

class T10StartupTest {
    static final Instant NOW = Instant.parse("2026-11-11T04:00:00Z");
    @TempDir Path directory;
    MemoryV2TestDatabase fixture;

    MemoryV2TestDatabase database() {
        return new MemoryV2TestDatabase(directory.resolve("startup.db"));
    }

    @BeforeEach
    void databaseSetup() {
        fixture = database();
    }

    @AfterEach
    void databaseClose() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock startupTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    ConfigurableApplicationContext start() {
        return start(fixture, directory, null);
    }

    static ConfigurableApplicationContext start(
            MemoryV2TestDatabase fixture, Path directory, PiRuntime delegate) {
        var application = new SpringApplicationBuilder(BotApplication.class, FixedTime.class);
        if (delegate != null) {
            application.initializers(
                    context ->
                            context.addBeanFactoryPostProcessor(
                                    factory ->
                                            factory.registerSingleton(
                                                    "piTextGenerator", delegate)));
        }
        return application.run(
                "--spring.profiles.active=" + ("sqlite"),
                "--spring.datasource.url=" + fixture.source.getUrl(),
                "--spring.datasource.password=",
                "--spring.datasource.hikari.maximum-pool-size=1",
                "--spring.flyway.enabled=true",
                "--server.port=0",
                "--bot.env-file=" + directory.resolve("no-env/.env"),
                "--bot.state-dir=" + directory.resolve("state"),
                "--bot.scheduling=false",
                "--bot.rag.enabled=false",
                "--bot.qq.enabled=false",
                "--bot.weixin.enabled=false",
                "--bot.pi.node=" + directory.resolve("no-external-node"),
                "--bot.pi.provider=test",
                "--bot.pi.model=test",
                "--bot.admin-password-hash=$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234",
                "--bot.memory.input-price=1",
                "--bot.memory.output-price=2",
                "--bot.memory.cache-read-price=0.1",
                "--bot.memory.cache-write-price=1.25",
                "--logging.level.root=WARN",
                "--spring.main.banner-mode=off");
    }

    @Test
    void L04ActualApplicationRestartPreservesV2AttemptSourcesAndSettlesOnlyUnfinishedCalls() {
        UUID group = fixture.group("restart"),
                v1 = UUID.randomUUID(),
                v2 = UUID.randomUUID(),
                outbox = UUID.randomUUID();
        UUID sent, unsent, terminal;
        try (var context = start()) {
            Db db = context.getBean(Db.class);
            new MemoryPolicyService(db).updateSettings(group, 0, "SUMMARY", 2, 6000, 30);
            UUID first = fixture.message(group, "first", "A", "v1原文", 1, NOW.minusSeconds(60)),
                    second = fixture.message(group, "second", "B", "v2原文", 2, NOW.minusSeconds(30));
            for (UUID id : List.of(v1, v2)) {
                db.exec(
                        "insert into memory_document(id,group_id,epoch,kind,state,source_count,strategy_version,attempt) values(?,?,2,'SUMMARY','RUNNING',1,?,?)",
                        id,
                        group,
                        id.equals(v1) ? 1 : 2,
                        id.equals(v1) ? 1 : 2);
                db.exec(
                        "insert into memory_document_source(document_id,message_id,kind) values(?,?,'SUMMARY')",
                        id,
                        id.equals(v1) ? first : second);
            }
            db.exec(
                    "insert into outbox(id,group_id,result_key,epoch,part_index,body,state) values(?,?,'sending',2,0,'待核实正文','SENDING')",
                    outbox,
                    group);
            var store = context.getBean(ModelCallStore.class);
            sent = begin(store, group);
            store.dispatched(sent);
            unsent = begin(store, group);
            terminal = begin(store, group);
            store.dispatched(terminal);
            store.usage(terminal, new ModelUsage(100L, 10L, 20L, 0L, 5L, UsageStatus.REPORTED));
            store.finish(terminal, "SUCCEEDED", null);
        }
        Map<String, Object> originalTerminal =
                fixture.db.one("select * from model_call where id=?", terminal);
        Object recoveredFinished;
        try (var context = start()) {
            Db db = context.getBean(Db.class);
            var failed = db.one("select * from memory_document where id=?", v2);
            assertEquals("FAILED", failed.get("state"));
            assertEquals("SERVICE_RESTART", failed.get("error_code"));
            assertEquals(2, ((Number) failed.get("attempt")).intValue());
            assertNotNull(failed.get("finished_at"));
            recoveredFinished = failed.get("finished_at");
            assertEquals(
                    1,
                    db.list("select * from memory_document_source where document_id=?", v2).size());
            assertEquals(
                    "QUEUED",
                    db.one("select state from memory_document where id=?", v1).get("state"));
            assertEquals(
                    2,
                    ((Number)
                                    db.one("select attempt from memory_document where id=?", v1)
                                            .get("attempt"))
                            .intValue());
            var sentRow = db.one("select * from model_call where id=?", sent);
            assertEquals("UNKNOWN", sentRow.get("state"));
            assertEquals("UNKNOWN", sentRow.get("usage_status"));
            assertNull(sentRow.get("reported_cost"));
            assertNotNull(sentRow.get("estimated_cost"));
            var unsentRow = db.one("select * from model_call where id=?", unsent);
            assertEquals("FAILED", unsentRow.get("state"));
            assertEquals("SERVICE_RESTART", unsentRow.get("error_code"));
            assertEquals(
                    0,
                    new java.math.BigDecimal(unsentRow.get("reported_cost").toString()).signum());
            assertNull(unsentRow.get("estimated_cost"));
            assertEquals(originalTerminal, db.one("select * from model_call where id=?", terminal));
            var sending = db.one("select * from outbox where id=?", outbox);
            assertEquals("UNKNOWN", sending.get("state"));
            assertNull(sending.get("event_seq"));
            assertNull(sending.get("delivered_at"));
            db.exec("update memory_document set state='CANCELLED' where id=?", v1);
            var archive = context.getBean(MarkdownArchive.class);
            for (int round = 0; round < 3; round++) {
                archive.prepare();
                for (int count = 0; count < 10; count++) {
                    var job = archive.claim();
                    if (job == null) {
                        break;
                    }
                    assertNotEquals(v2, job.get("id"));
                    archive.process(job, context.getBean(TextGenerator.class));
                }
            }
            assertEquals(3, db.list("select id from model_call").size());
            assertEquals(
                    "FAILED",
                    db.one("select state from memory_document where id=?", v2).get("state"));
        }
        try (var context = start()) {
            Db db = context.getBean(Db.class);
            assertEquals(
                    recoveredFinished,
                    db.one("select finished_at from memory_document where id=?", v2)
                            .get("finished_at"));
            assertEquals(
                    2,
                    ((Number)
                                    db.one("select attempt from memory_document where id=?", v2)
                                            .get("attempt"))
                            .intValue());
            assertEquals(originalTerminal, db.one("select * from model_call where id=?", terminal));
            assertEquals(3, db.list("select id from model_call").size());
        }
    }

    @Test
    void L03ActualHourlyEntryDeletesOnlyOlderThanNinetyDays() throws Exception {
        UUID group = fixture.group("retention");
        try (var context = start()) {
            var store = context.getBean(ModelCallStore.class);
            UUID older = begin(store, group),
                    boundary = begin(store, group),
                    newer = begin(store, group);
            for (UUID id : List.of(older, boundary, newer)) {
                store.finish(id, "FAILED", "NOT_SENT");
            }
            var db = context.getBean(Db.class);
            Instant cutoff = NOW.minus(Duration.ofDays(90));
            db.exec("update model_call set created_at=? where id=?", cutoff.minusMillis(1), older);
            db.exec("update model_call set created_at=? where id=?", cutoff, boundary);
            db.exec("update model_call set created_at=? where id=?", cutoff.plusMillis(1), newer);
            Map<String, Object> original = db.one("select * from model_call where id=?", boundary);
            Object entry = context.getBean("memoryUsageLifecycle");
            var method = entry.getClass().getMethod("cleanup");
            var scheduled = method.getAnnotation(Scheduled.class);
            assertNotNull(scheduled);
            assertEquals(3600000, scheduled.fixedDelay());
            method.invoke(entry);
            assertNull(db.one("select id from model_call where id=?", older));
            assertEquals(original, db.one("select * from model_call where id=?", boundary));
            assertNotNull(db.one("select id from model_call where id=?", newer));
        }
    }

    UUID begin(ModelCallStore store, UUID group) {
        var request =
                new GenerationRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        group,
                        2,
                        Purpose.MEMORY_SUMMARY,
                        "s",
                        "q".repeat(743),
                        8000,
                        800,
                        30);
        store.begin(request);
        return request.callId();
    }
}
