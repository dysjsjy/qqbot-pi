/**
 * [INPUT]: 使用临时 V13/V14 SQLite、固定时钟与合成生成端口。
 * [OUTPUT]: 验证迁移、冷启动、预算、重启、聊天隔离与本人退出。
 * [POS]: 实验功能冒烟；不连接真实聊天平台或模型。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.memory.*;
import dev.qqbot.message.*;
import dev.qqbot.model.*;
import dev.qqbot.usage.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class MemberProfileSmokeTest {
    private static final Instant NOW = Instant.parse("2026-11-21T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final MemberProfileProperties ON =
            new MemberProfileProperties(true, 30000, 500, 4);
    private static final MemoryProperties BUDGETS = MemoryProperties.defaults();
    private static final String SENDER = "fixture-member";
    @TempDir Path directory;

    @Test
    void migratesLedgerWithExistingRows() {
        Path path = directory.resolve("upgrade.db");
        var legacy = UUID.randomUUID();
        UUID group;
        try (var old = new MemoryV2TestDatabase(path, "13")) {
            group = old.group("migration");
            var calls = new ModelCallStore(old.db, bot(), BUDGETS, CLOCK);
            calls.begin(request(group, legacy, Purpose.CHAT));
            calls.dispatched(legacy);
            calls.finish(legacy, "SUCCEEDED", null);
        }
        try (var upgraded = new MemoryV2TestDatabase(path)) {
            assertEquals(
                    "SUCCEEDED",
                    upgraded.db
                            .one("select state from model_call where id=?", legacy)
                            .get("state"));
            var current = UUID.randomUUID();
            new ModelCallStore(upgraded.db, bot(), BUDGETS, CLOCK)
                    .begin(request(group, current, Purpose.MEMBER_PROFILE));
            assertEquals(
                    "MEMBER_PROFILE",
                    upgraded.db
                            .one("select purpose from model_call where id=?", current)
                            .get("purpose"));
            assertTrue(upgraded.db.list("pragma foreign_key_check").isEmpty());
        }
    }

    @Test
    void onlyFreshMentionsChangeTheSendersProfile() {
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("commands.db"))) {
            var db = fixture.db;
            UUID group = fixture.group("commands");
            var policies = new MemberProfilePolicyService(db, ON, CLOCK);
            var stats = new MemberProfileStats(db);
            var service =
                    new MemberProfileService(
                            db, CLOCK, RetentionProperties.defaults(), policies, stats);
            policies.save(group, policy(false, 0));
            assertTrue(service.self(group, "other-member", "off").contains("已退出"));
            var replyPolicies = new ReplyPolicyService(db);
            var replies = new ReplyQueue(db);
            var admission = new RunAdmission(db, replies, replyPolicies);
            var commands =
                    new CommandHandler(null, replies, null, db, replyPolicies, CLOCK, service);
            var ingest =
                    new IngestService(
                            new ScopeRegistry(db),
                            new MessageJournal(db, bot(), CLOCK),
                            replyPolicies,
                            null,
                            commands,
                            admission);
            assertFalse(ingest.ingest(profileCommand("unmentioned", "off", false)).accepted());
            assertFalse(
                    ingest.ingest(profileCommand("stale", "off", true, NOW.minusSeconds(301)))
                            .accepted());
            assertNull(db.one("select id from member_profile where sender_id=?", SENDER));
            assertTrue(ingest.ingest(profileCommand("opt-out", "off", true)).accepted());
            assertTrue(ingest.ingest(profileCommand("opt-out", "off", true)).duplicate());
            assertTrue(service.detail(group, SENDER).member().optedOut());
            var status = ingest.ingest(profileCommand("status", "status", true));
            assertEquals(
                    "你已退出本群实验性成员画像。",
                    db.one(
                                    "select body from outbox where reply_to_message_id=?",
                                    status.messageId())
                            .get("body"));
            assertTrue(ingest.ingest(profileCommand("opt-in", "on", true)).accepted());
            assertFalse(service.detail(group, SENDER).member().optedOut());
            assertTrue(ingest.ingest(profileCommand("clear", "clear", true)).accepted());
            var state =
                    db.one(
                            "select source_floor_seq,last_profiled_seq from member_profile where sender_id=?",
                            SENDER);
            assertEquals(state.get("last_profiled_seq"), state.get("source_floor_seq"));
            assertTrue(service.detail(group, "other-member").member().optedOut());
            assertTrue(db.list("select id from agent_run").isEmpty());
        }
    }

    @Test
    void interruptedJobRemainsFailedWithoutAnotherModelCall() {
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("restart.db"))) {
            var db = fixture.db;
            UUID group = fixture.group("restart");
            for (int seq = 1; seq <= 5; seq++) {
                fixture.message(
                        group,
                        "message-" + seq,
                        SENDER,
                        "继续整理容器运维经验",
                        seq,
                        NOW.minus(Duration.ofDays(seq)));
            }
            var policies = new MemberProfilePolicyService(db, ON, CLOCK);
            var stats = new MemberProfileStats(db);
            var service =
                    new MemberProfileService(
                            db, CLOCK, RetentionProperties.defaults(), policies, stats);
            policies.save(group, policy(false, 0));
            UUID job =
                    service.refresh(group, new MemberProfileService.Refresh(SENDER, "REPLAY"))
                            .get(0);
            db.exec("update member_profile_job set state='RUNNING' where id=?", job);
            var call = UUID.randomUUID();
            var ledger = new ModelCallStore(db, bot(), BUDGETS, CLOCK);
            ledger.begin(request(group, call, Purpose.MEMBER_PROFILE));
            ledger.dispatched(call);
            var delegate = mock(TextGenerator.class);
            var extractor =
                    new MemberProfileExtractor(
                            db,
                            CLOCK,
                            RetentionProperties.defaults(),
                            BUDGETS,
                            new ProfileGuardrails(new ObjectMapper()),
                            delegate,
                            policies);
            try (var worker =
                    new CloseWorker(
                            new MemberProfileWorker(
                                    db, CLOCK, policies, stats, service, extractor, delegate,
                                    null))) {
                worker.value().recover();
                assertFalse(worker.value().runOnce());
                assertEquals("SERVICE_RESTART", service.jobs(group, 1).get(0).get("error_code"));
                assertEquals("FAILED", service.jobs(group, 1).get(0).get("state"));
                assertEquals(1, db.list("select id from model_call where dispatched").size());
                verifyNoInteractions(delegate);
            }
        }
    }

    @Test
    void coldStartReflectsOnlyOwnSourcesAndKeepsChatGuarded() {
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("profile.db"))) {
            var db = fixture.db;
            UUID group = fixture.group("profile");
            var first =
                    fixture.message(
                            group,
                            "cold-one",
                            SENDER,
                            "我常用 Rust 排查服务端性能",
                            1,
                            NOW.minus(Duration.ofDays(44)));
            var second =
                    fixture.message(
                            group,
                            "cold-two",
                            SENDER,
                            "我熟悉 Docker 和 Rust 的常规排障",
                            2,
                            NOW.minus(Duration.ofDays(42)));
            fixture.message(
                    group, "other", "other-member", "这段只能作为前文", 3, NOW.minus(Duration.ofDays(41)));
            for (int seq = 4; seq <= 7; seq++) {
                fixture.message(
                        group,
                        "cold-" + seq,
                        SENDER,
                        "继续补充 Rust 排错经验",
                        seq,
                        NOW.minus(Duration.ofDays(40)).plusSeconds(seq));
            }
            var json = new ObjectMapper();
            var retention = RetentionProperties.defaults();
            var policies = new MemberProfilePolicyService(db, ON, CLOCK);
            var stats = new MemberProfileStats(db);
            var service = new MemberProfileService(db, CLOCK, retention, policies, stats);
            var delegate = mock(TextGenerator.class);
            var busy = new AtomicBoolean(true);
            when(delegate.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                    .thenAnswer(
                            invocation -> {
                                GenerationRequest request = invocation.getArgument(0);
                                assertTrue(request.purpose().background());
                                assertEquals(Purpose.MEMBER_PROFILE, request.purpose());
                                if (busy.getAndSet(false)) {
                                    throw new IllegalStateException("BACKGROUND_BUSY");
                                }
                                invocation.<GenerationObserver>getArgument(1).dispatched();
                                if (request.userPrompt().contains("本批发言")) {
                                    assertTrue(request.userPrompt().contains(first.toString()));
                                    return new TextGenerator.Result(
                                            db.json(
                                                    Map.of(
                                                            "ops",
                                                            List.of(
                                                                    Map.of(
                                                                            "action",
                                                                            "ADD",
                                                                            "category",
                                                                            "EXPERTISE",
                                                                            "statement",
                                                                            "熟悉 Rust 和 Docker 排障",
                                                                            "confidence",
                                                                            0.8,
                                                                            "sourceMessageIds",
                                                                            List.of(
                                                                                    first,
                                                                                    second))))),
                                            null);
                                }
                                var observation =
                                        db.one(
                                                "select id from member_profile_observation where state='CONFIRMED'");
                                return new TextGenerator.Result(
                                        db.json(
                                                Map.of(
                                                        "observationIds",
                                                        List.of(observation.get("id")))),
                                        null);
                            });
            var metered =
                    new MeteredTextGenerator(
                            delegate, new ModelCallStore(db, bot(), BUDGETS, CLOCK));
            var extractor =
                    new MemberProfileExtractor(
                            db,
                            CLOCK,
                            retention,
                            BUDGETS,
                            new ProfileGuardrails(json),
                            metered,
                            policies);
            try (var worker =
                    new CloseWorker(
                            new MemberProfileWorker(
                                    db, CLOCK, policies, stats, service, extractor, metered,
                                    null))) {
                assertFalse(worker.value().runOnce());
                verifyNoInteractions(delegate);
                policies.save(group, policy(false, 0L));
                assertTrue(worker.value().runOnce());
                assertEquals(false, db.one("select dispatched from model_call").get("dispatched"));
                assertEquals("QUEUED", service.jobs(group, 1).get(0).get("state"));
                assertTrue(worker.value().runOnce());
                assertTrue(worker.value().runOnce());
                var detail = service.detail(group, SENDER);
                assertEquals("熟悉 Rust 和 Docker 排障", detail.member().profileCard());
                assertEquals(
                        Set.of(first, second),
                        new HashSet<>(
                                detail.observations().get(0).sources().stream()
                                        .map(MemberProfileService.Source::id)
                                        .toList()));
                var assembler = new ContextAssembler(BUDGETS, json, ON);
                var profiles = new MemberProfileContext(db, ON);
                var scope = new MemoryScope(group, 2, null, 8, NOW, NOW);
                var chat =
                        new ContextRequest(
                                scope, UUID.randomUUID(), 0, SENDER, "怎么排查这个问题？", QueryMode.CHAT);
                var history =
                        new ContextRequest(
                                scope,
                                chat.conversationId(),
                                0,
                                SENDER,
                                "何时讨论过？",
                                QueryMode.HISTORY);
                var original = assembler.assemble(chat, "", List.of(), List.of(), "");
                var noInjection =
                        assembler.assemble(
                                chat, "", List.of(), List.of(), "", profiles.load(chat, List.of()));
                assertEquals(original.systemPrompt(), noInjection.systemPrompt());
                assertEquals(original.userPrompt(), noInjection.userPrompt());
                policies.save(group, policy(true, 1L));
                var enabled =
                        assembler.assemble(
                                chat, "", List.of(), List.of(), "", profiles.load(chat, List.of()));
                assertTrue(enabled.userPrompt().contains("熟悉 Rust"));
                assertTrue(enabled.stats().layerTokens().get("profile") <= 500);
                assertEquals(
                        0,
                        assembler
                                .assemble(
                                        history,
                                        "",
                                        List.of(),
                                        List.of(),
                                        "",
                                        profiles.load(history, List.of()))
                                .stats()
                                .layerTokens()
                                .get("profile"));

                service.edit(
                        group,
                        new MemberProfileService.Edit(
                                SENDER, "偏好简明讨论", null, false, false, detail.member().version()));
                db.exec("update member_profile set active_slot=0 where group_id=?", group);
                assertEquals(
                        1,
                        service.refresh(group, new MemberProfileService.Refresh(SENDER, "REPLAY"))
                                .size());
                assertTrue(worker.value().runOnce());
                assertEquals("偏好简明讨论", service.detail(group, SENDER).member().adminNote());
                assertEquals(
                        429,
                        assertThrows(
                                        ResponseStatusException.class,
                                        () ->
                                                service.refresh(
                                                        group,
                                                        new MemberProfileService.Refresh(
                                                                SENDER, "REPLAY")))
                                .getStatusCode()
                                .value());
                assertTrue(service.self(group, SENDER, "off").contains("已退出"));
                assertTrue(service.detail(group, SENDER).member().optedOut());
                assertTrue(service.detail(group, SENDER).observations().isEmpty());
                assertTrue(profiles.load(chat, List.of()).cards().isEmpty());
            }
        }
    }

    private static MemberProfilePolicyService.Policy policy(boolean inject, long revision) {
        return new MemberProfilePolicyService.Policy(
                true, inject, 5, 5, 2, 90, 5, 24, 3, 60, "SUBTLE", revision);
    }

    private static GroupIngestRequest profileCommand(String key, String command, boolean mention) {
        return profileCommand(key, command, mention, NOW);
    }

    private static GroupIngestRequest profileCommand(
            String key, String command, boolean mention, Instant when) {
        return new GroupIngestRequest(
                "QQ",
                "test",
                "commands",
                key,
                SENDER,
                "合成成员",
                when,
                "/profile " + command,
                mention,
                false);
    }

    private static GenerationRequest request(UUID group, UUID call, Purpose purpose) {
        return new GenerationRequest(
                UUID.randomUUID(), call, group, 2, purpose, "system", "test", 1024, 600, 10);
    }

    private BotProperties bot() {
        var gateway = new BotProperties.Gateway(false, "", "", "test", "");
        return new BotProperties(
                "admin",
                "",
                directory,
                false,
                new BotProperties.Pi("node", "unused", "test", "test", "KEY", "", 5, ""),
                new BotProperties.Embedding("", "", "", 0, 0),
                new BotProperties.Qq(
                        false, new BotProperties.OfficialQq("test", "", "", "", false)),
                gateway,
                new BotProperties.Rag(false));
    }

    private record CloseWorker(MemberProfileWorker value) implements AutoCloseable {
        public void close() {
            value.close();
        }
    }
}
