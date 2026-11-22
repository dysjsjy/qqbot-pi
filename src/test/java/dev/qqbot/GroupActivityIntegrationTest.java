/**
 * [INPUT]: 依赖临时 SQLite、固定时钟和合成群聊事件。
 * [OUTPUT]: 验证成员范围、授权、命令去重、榜单、投递与清理。
 * [POS]: 集成验证不发送真实群消息，也不调用模型。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.channel.OfficialQqEvents;
import dev.qqbot.channel.OutboxWorker;
import dev.qqbot.delivery.MessageTransport;
import dev.qqbot.management.*;
import dev.qqbot.memory.*;
import dev.qqbot.message.*;
import dev.qqbot.persistence.Db;
import dev.qqbot.schedule.*;
import jakarta.validation.Validation;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class GroupActivityIntegrationTest {
    private static final Instant TICK = Instant.parse("2026-11-18T01:10:00Z");
    private static final Instant YESTERDAY = Instant.parse("2026-11-16T16:00:00Z");
    private static final Clock ACTIVITY_CLOCK = Clock.fixed(TICK, ZoneOffset.UTC);
    private static final LocalTime MORNING = LocalTime.of(9, 0);
    @TempDir Path directory;
    private MemoryV2TestDatabase fixture;
    private Db db;

    @BeforeEach
    void setup() {
        fixture = new MemoryV2TestDatabase(directory.resolve("activity.db"));
        db = fixture.db;
    }

    @Test
    void migrationPreservesExistingAdministratorsAndReplySettings() {
        Path path = directory.resolve("upgrade.db");
        UUID group;
        try (var old = new MemoryV2TestDatabase(path, "11")) {
            group = old.group("old");
            old.db.exec("update group_config set admin_ids='first,second' where id=?", group);
            old.db.exec(
                    "insert into reply_policy(group_id,mode,energy,energy_gain,energy_cost) values(?,'ENERGY',4,2,7)",
                    group);
            old.db.exec(
                    "insert into daily_schedule(group_id,enabled,local_time,next_run_at) values(?,true,'09:00',?)",
                    group,
                    TICK);
        }
        try (var migrated = new MemoryV2TestDatabase(path)) {
            assertEquals(
                    "first,second",
                    migrated.db
                            .one("select admin_ids from group_config where id=?", group)
                            .get("admin_ids"));
            assertEquals(0L, new ReplyPolicyService(migrated.db).get(group).get("revision"));
            assertEquals(4, new ReplyPolicyService(migrated.db).get(group).get("energy"));
            assertEquals(
                    false,
                    new ActivityScheduleService(migrated.db, ACTIVITY_CLOCK)
                            .get(group)
                            .get("enabled"));
            assertEquals(
                    true,
                    migrated.db
                            .one("select enabled from daily_schedule where group_id=?", group)
                            .get("enabled"));
            assertEquals(
                    0,
                    ((Number) migrated.db.one("select count(*) n from outbox").get("n"))
                            .intValue());
        }
    }

    @Test
    void memberWindowsSearchRolesAndWritesStayWithinGroupAccountAndEpoch() {
        UUID group = fixture.group("same-room"),
                other = otherAccount("same-room"),
                direct = fixture.group("direct");
        db.exec("update group_config set chat_type='PRIVATE' where id=?", direct);
        db.exec(
                "update group_config set admin_ids='never-spoke, already,already' where id=?",
                group);
        sample(group, "first", "first-id", "旧昵称", TICK.minusSeconds(20), TICK, 2, "TEXT", 1);
        sample(group, "nickname", "first-id", "同名", TICK.minusSeconds(20), TICK, 2, "NON_TEXT", 2);
        sample(group, "second", "second-id", "同名", TICK.minusSeconds(10), TICK, 2, "TEXT", 3);
        sample(
                group,
                "window",
                "second-id",
                "旧昵称",
                TICK.minus(Duration.ofDays(7)),
                TICK,
                2,
                "TEXT",
                4);
        sample(
                group,
                "history",
                "history-id",
                "旧成员",
                TICK.minus(Duration.ofDays(8)),
                TICK,
                2,
                "TEXT",
                5);
        sample(
                group,
                "cold",
                "cold-id",
                "冷窗成员",
                TICK.minus(Duration.ofDays(31)),
                TICK,
                2,
                "TEXT",
                6);
        sample(group, "command", "first-id", "隐藏", TICK, TICK, 2, "COMMAND", 7);
        sample(group, "old-epoch", "old-id", "隐藏", TICK, TICK, 1, "TEXT", 8);
        sample(other, "other", "first-id", "另一账号", TICK, TICK, 2, "TEXT", 1);
        sample(other, "remote", "remote-id", "别的群成员", TICK, TICK, 2, "TEXT", 2);
        var members = new GroupMembers(db, ACTIVITY_CLOCK);
        var admin = administration(ACTIVITY_CLOCK);
        var page = members.list(group, 7, "同名", "messages", false, 1, 0);
        assertEquals(2, ((Number) page.get("total")).intValue());
        assertEquals("second-id", member(page, 0).senderId());
        assertEquals(
                "first-id",
                member(members.list(group, 7, "同名", "messages", false, 1, 1), 0).senderId());
        assertTrue(
                ((List<?>) members.list(group, 7, "", "messages", false, 1, 100).get("members"))
                        .isEmpty());
        assertEquals(
                3,
                ((Number) members.list(group, 30, "", "recent", false, 25, 0).get("total"))
                        .intValue());
        assertEquals(
                0, ((Number) members.detail(group, "never-spoke").get("messages30d")).intValue());
        assertEquals(2, ((Number) members.detail(group, "first-id").get("messages7d")).intValue());
        assertEquals("同名", member(page, 0).senderName());
        assertEquals(0, ((Number) members.detail(group, "cold-id").get("messages30d")).intValue());
        assertEquals(
                1,
                ((Number) members.list(group, 90, "cold-id", "recent", false, 25, 0).get("total"))
                        .intValue());
        assertEquals(
                2,
                ((Number) members.list(group, 7, "", "recent", true, 25, 0).get("total"))
                        .intValue());
        for (String hidden : List.of("remote-id", "old-id")) {
            assertEquals(404, status(() -> members.detail(group, hidden)));
        }
        assertEquals(404, status(() -> members.list(direct, 7, "", "recent", false, 25, 0)));
        assertEquals(
                404, status(() -> admin.setMemberAdmin(group, "remote-id", true, 0, "web-admin")));
        assertThrows(
                IllegalArgumentException.class,
                () -> admin.setMemberAdmin(group, "first-id,remote-id", true, 0, "web-admin"));
        assertEquals(
                true, admin.setMemberAdmin(group, "first-id", true, 0, "web-admin").get("admin"));
        assertTrue(((GroupMembers.Member) members.detail(group, "first-id").get("member")).admin());
        assertFalse(
                ((GroupMembers.Member) members.detail(other, "first-id").get("member")).admin());
        var edit =
                new GroupSettings(
                        "QQ", "test", "same-room", "same-room", true, true, "never-spoke", 0);
        assertEquals(409, status(() -> admin.update(group, edit)));
        assertEquals(
                409, status(() -> admin.setMemberAdmin(group, "first-id", false, 0, "web-admin")));
        assertEquals(
                false,
                admin.setMemberAdmin(group, "never-spoke", false, 1, "web-admin").get("admin"));
        assertEquals(
                "already,first-id",
                db.one("select admin_ids from group_config where id=?", group).get("admin_ids"));
        var details =
                db.one("select details from admin_audit where action='GROUP_ADMIN_GRANT'")
                        .get("details")
                        .toString();
        assertTrue(details.contains("web-admin") && details.contains("first-id"));
    }

    @Test
    void genuineQqMentionsRunOneVersionedPolicyMutationAndRevocationIsImmediate() throws Exception {
        Instant now = Instant.now();
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        UUID group = fixture.group("qq-room"), other = fixture.group("other-room");
        var props = properties(false);
        var policies = new ReplyPolicyService(db);
        var ingest = ingest(props, policies, clock);
        accept(
                ingest,
                event("QQ", "test", "qq-room", "member-message", "trusted", "普通发言", now, false));
        var admin = administration(clock);
        admin.setMemberAdmin(group, "trusted", true, 0, "web-admin");
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var official = new OfficialQqEvents(props, ingest, validators.getValidator());
            String energy = "/bot reply energy 2 10";
            official(official, "GROUP_MESSAGE_CREATE", "upgrade", "trusted", energy, now);
            assertEquals("MENTION", policies.get(group).get("mode"));
            assertEquals(0, count("select count(*) n from outbox"));
            official(
                    official,
                    "GROUP_AT_MESSAGE_CREATE",
                    "upgrade",
                    "different-author",
                    energy,
                    now);
            official(
                    official,
                    "GROUP_AT_MESSAGE_CREATE",
                    "upgrade",
                    "trusted",
                    "/bot reply energy 3 10",
                    now);
            assertEquals("MENTION", policies.get(group).get("mode"));
            official(official, "GROUP_AT_MESSAGE_CREATE", "upgrade", "trusted", energy, now);
            official(official, "GROUP_AT_MESSAGE_CREATE", "upgrade", "trusted", energy, now);
            official(official, "GROUP_MESSAGE_CREATE", "upgrade", "trusted", energy, now);
            assertEquals(1, count("select count(*) n from outbox"));
            assertEquals(
                    1,
                    count("select count(*) n from admin_audit where action='REPLY_POLICY_UPDATE'"));
            assertEquals(1L, policies.get(group).get("revision"));
            assertEquals("ENERGY", policies.get(group).get("mode"));
            assertEquals(2, policies.get(group).get("energy_gain"));
            official(
                    official, "GROUP_AT_MESSAGE_CREATE", "show", "trusted", "/bot reply show", now);
            assertEquals(2, count("select count(*) n from outbox where body like '%当前能量：0%'"));
            official(official, "GROUP_AT_MESSAGE_CREATE", "status", "trusted", "/bot status", now);
            assertEquals(1, count("select count(*) n from outbox where body like '%每日活跃榜：未启用%'"));

            official(
                    official,
                    "GROUP_MESSAGE_CREATE",
                    "fake",
                    "trusted",
                    "@self /bot reply mode mention",
                    now);
            official(
                    official,
                    "GROUP_MESSAGE_CREATE",
                    "different-bot",
                    "trusted",
                    "/bot reply mode mention <@other-bot>",
                    now);
            official(
                    official,
                    "GROUP_AT_MESSAGE_CREATE",
                    "forbidden",
                    "stranger",
                    "/bot reply mode mention",
                    now);
            official(
                    official,
                    "GROUP_AT_MESSAGE_CREATE",
                    "bad",
                    "trusted",
                    "/bot reply probability 1.000001",
                    now);
            official(
                    official,
                    "GROUP_AT_MESSAGE_CREATE",
                    "expired",
                    "trusted",
                    "/bot reply mode mention",
                    now.minusSeconds(301));
            official(
                    official,
                    "GROUP_AT_MESSAGE_CREATE",
                    "future",
                    "trusted",
                    "/bot reply mode mention",
                    now.plusSeconds(31));
            assertEquals("ENERGY", policies.get(group).get("mode"));
            accept(
                    ingest,
                    event(
                            "QQ",
                            "test",
                            "other-room",
                            "cross",
                            "trusted",
                            "/bot reply mode energy",
                            now,
                            true));
            assertEquals("MENTION", policies.get(other).get("mode"));

            int energyBefore = ((Number) policies.get(group).get("energy")).intValue();
            official(official, "GROUP_AT_MESSAGE_CREATE", "no-change", "trusted", energy, now);
            assertEquals(energyBefore, policies.get(group).get("energy"));
            assertEquals(1L, policies.get(group).get("revision"));
            assertEquals(
                    409,
                    status(
                            () ->
                                    policies.save(
                                            group,
                                            new ReplyPolicyService.Patch("MENTION", 0.1, 1, 10),
                                            0L,
                                            "WEB",
                                            "web-admin")));
            official(
                    official,
                    "GROUP_AT_MESSAGE_CREATE",
                    "prob",
                    "trusted",
                    "/bot reply probability 0",
                    now);
            assertEquals(2, policies.get(group).get("energy_gain"));
            assertFalse(policies.shouldReply(group));
            official(
                    official,
                    "GROUP_AT_MESSAGE_CREATE",
                    "mention",
                    "trusted",
                    "/bot reply mode mention",
                    now);
            assertFalse(policies.shouldReply(group));
            assertEquals(0, count("select count(*) n from agent_run"));
            assertEquals(0, count("select count(*) n from daily_run"));
            official(
                    official,
                    "GROUP_MESSAGE_CREATE",
                    "summary-old",
                    "trusted",
                    "/summary yesterday",
                    now);
            assertEquals(0, count("select count(*) n from daily_run"));
            official(
                    official,
                    "GROUP_MESSAGE_CREATE",
                    "summary-real",
                    "trusted",
                    "<@bot-self> /summary yesterday",
                    now);
            assertEquals(1, count("select count(*) n from daily_run"));

            admin.setMemberAdmin(group, "trusted", false, 1, "web-admin");
            official(official, "GROUP_AT_MESSAGE_CREATE", "revoked", "trusted", energy, now);
            assertEquals("MENTION", policies.get(group).get("mode"));
            assertEquals(
                    3,
                    count("select count(*) n from admin_audit where action='REPLY_POLICY_UPDATE'"));
        }
    }

    @Test
    void bridgeUsesTrustedMentionAndReplayAfterClearCannotRestoreASetting() {
        Instant now = Instant.now();
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        var props = properties(false);
        UUID group = fixture.group("wx-room");
        db.exec(
                "update group_config set channel='WEIXIN',account_id='bridge',admin_ids='trusted' where id=?",
                group);
        var policies = new ReplyPolicyService(db);
        var ingest = ingest(props, policies, clock);
        accept(
                ingest,
                event(
                        "WEIXIN",
                        "bridge",
                        "wx-room",
                        "manual-at",
                        "trusted",
                        "/bot reply probability 1",
                        now,
                        false));
        assertEquals("MENTION", policies.get(group).get("mode"));
        GroupIngestRequest addressed =
                event(
                        "WEIXIN",
                        "bridge",
                        "wx-room",
                        "real-at",
                        "trusted",
                        "/bot reply probability 1",
                        now,
                        true);
        accept(ingest, addressed);
        assertTrue(policies.shouldReply(group));
        assertEquals(0, count("select count(*) n from agent_run"));
        db.tx(
                () -> {
                    maintenance(clock).clear(group);
                    return null;
                });
        policies.save(
                group,
                new ReplyPolicyService.Patch("MENTION", null, null, null),
                1L,
                "WEB",
                "web-admin");
        accept(ingest, addressed);
        assertEquals("MENTION", policies.get(group).get("mode"));
        assertEquals(
                "trusted",
                db.one("select admin_ids from group_config where id=?", group).get("admin_ids"));
        var privateRequest =
                new GroupIngestRequest(
                        "QQ",
                        "test",
                        "trusted",
                        "dm",
                        "trusted",
                        "trusted",
                        now,
                        "/bot reply probability 1",
                        true,
                        false,
                        "TEXT",
                        true);
        var privateResult = accept(ingest, privateRequest);
        assertEquals(404, status(() -> policies.get(privateResult.groupId())));
    }

    @Test
    void rankingFreezesWindowTopAndCoverageEvenWithoutRepliesOrPostingPermission() {
        UUID group = fixture.group("main-room"),
                empty = fixture.group("empty"),
                blocked = fixture.group("blocked"),
                other = fixture.group("not-due");
        var settings = settingsBeforeTick();
        db.exec("update group_config set reply_enabled=false where id=?", group);
        settings.save(group, true, MORNING, 2, "CONSOLE_ONLY", 0);
        settings.save(empty, true, MORNING, 10, "GROUP", 0);
        settings.save(blocked, true, MORNING, 10, "GROUP", 0);
        settings.save(other, true, LocalTime.of(11, 0), 10, "GROUP", 0);
        db.exec(
                "insert into collection_gap(id,group_id,starts_at,ends_at,reason) values(?,?,?,?,?)",
                UUID.randomUUID(),
                group,
                YESTERDAY,
                YESTERDAY.plusSeconds(5),
                "TEST");
        sample(group, "start", "member-alpha", "旧名", YESTERDAY, TICK, 2, "TEXT", 1);
        sample(
                group,
                "last-alpha",
                "member-alpha",
                "新名 <@123>\nhttps://sample.example user@example.org member-alpha",
                YESTERDAY.plusSeconds(60),
                TICK,
                2,
                "NON_TEXT",
                2);
        sample(group, "b1", "member-beta", "同名", YESTERDAY.plusSeconds(30), TICK, 2, "TEXT", 3);
        sample(group, "b2", "member-beta", "同名", YESTERDAY.plusSeconds(60), TICK, 2, "TEXT", 4);
        sample(group, "one", "member-other", "另一个", YESTERDAY.plusSeconds(70), TICK, 2, "TEXT", 5);
        sample(
                group,
                "end",
                "member-other",
                "未计入",
                YESTERDAY.plus(Duration.ofDays(1)),
                TICK,
                2,
                "TEXT",
                6);
        sample(group, "old", "member-beta", "未计入", YESTERDAY, TICK, 1, "TEXT", 7);
        sample(group, "command", "member-beta", "未计入", YESTERDAY, TICK, 2, "COMMAND", 8);
        sample(
                group,
                "future-receipt",
                "member-beta",
                "未计入",
                YESTERDAY,
                TICK.plusSeconds(1),
                2,
                "TEXT",
                9);
        sample(blocked, "blocked", "member", "member", YESTERDAY, TICK, 2, "TEXT", 1);
        db.exec(
                "insert into daily_run(id,group_id,business_date,epoch,trigger_type) values(?,?,?,2,'SCHEDULE')",
                UUID.randomUUID(),
                group,
                LocalDate.of(2026, 11, 17));
        var service = new DailyActivityService(db, ACTIVITY_CLOCK, properties(false));
        service.scheduleDue();
        var first = run(service, group);
        assertEquals("2026-11-17", first.get("business_date").toString());
        assertEquals("NOT_REQUESTED", first.get("delivery_state"));
        assertEquals(true, ((Map<?, ?>) first.get("coverage")).get("partial"));
        var ranking = (List<?>) first.get("ranking");
        assertEquals(2, ranking.size());
        assertEquals("新名", ((Map<?, ?>) ranking.get(0)).get("nickname"));
        assertEquals(2, ((Map<?, ?>) ranking.get(0)).get("messageCount"));
        assertEquals("同名", ((Map<?, ?>) ranking.get(1)).get("nickname"));
        assertEquals("NO_DATA", run(service, empty).get("state"));
        assertEquals("BLOCKED", run(service, blocked).get("delivery_state"));
        assertEquals("QQ_ACTIVE_MESSAGES_DISABLED", run(service, blocked).get("error_code"));
        assertEquals(0, ((Number) service.runs(other, 5, 0).get("total")).intValue());
        assertEquals(1, count("select count(*) n from daily_run"));
        assertEquals(0, count("select count(*) n from outbox"));
        sample(group, "late", "member-other", "迟到", YESTERDAY, TICK.plusSeconds(2), 2, "TEXT", 10);
        db.exec("update activity_schedule set next_run_at=? where group_id=?", TICK, group);
        new DailyActivityService(db, ACTIVITY_CLOCK, properties(false)).scheduleDue();
        assertEquals(first, run(service, group));
        assertEquals(3, count("select count(*) n from activity_run"));
    }

    @Test
    void outboxSeparatesSentFailedUnknownAndCancelledAndClearsExpiredPayloads() {
        var props = properties(true);
        var settings = settingsBeforeTick();
        var service = new DailyActivityService(db, ACTIVITY_CLOCK, props);
        UUID sent = fixture.group("sent"),
                failed = fixture.group("failed"),
                unknown = fixture.group("unknown"),
                stopped = fixture.group("stopped"),
                muted = fixture.group("muted");
        for (UUID group : List.of(sent, failed, unknown, stopped, muted)) {
            settings.save(group, true, MORNING, 10, "GROUP", 0);
            sample(group, "payload", "real-open-id", "成员昵称", YESTERDAY, TICK, 2, "TEXT", 1);
        }
        db.exec("update group_config set reply_enabled=false where id=?", muted);
        service.scheduleDue();
        assertEquals("GROUP_REPLY_DISABLED", run(service, muted).get("error_code"));
        assertEquals(4, count("select count(*) n from outbox"));
        assertEquals(
                409, status(() -> settings.save(stopped, true, MORNING, 10, "CONSOLE_ONLY", 0)));
        settings.save(stopped, true, MORNING, 10, "CONSOLE_ONLY", 1);
        var delivered = new ArrayList<MessageTransport.Message>();
        MessageTransport transport =
                message -> {
                    delivered.add(message);
                    assertNull(message.reply());
                    assertTrue(message.text().contains("成员昵称"));
                    assertFalse(message.text().contains("real-open-id"));
                    return switch (message.target()) {
                        case "sent" ->
                                new MessageTransport.Delivery(
                                        MessageTransport.State.ACCEPTED, "receipt", null);
                        case "failed" ->
                                new MessageTransport.Delivery(
                                        MessageTransport.State.FAILED, null, "PLATFORM_DENIED");
                        case "unknown" ->
                                new MessageTransport.Delivery(
                                        MessageTransport.State.UNKNOWN, null, "NO_RECEIPT");
                        default ->
                                throw new AssertionError(
                                        "Unexpected publication: " + message.target());
                    };
                };
        db.exec("update outbox set next_attempt_at=0");
        var worker = new OutboxWorker(db, transport, props, ACTIVITY_CLOCK);
        worker.tick();
        worker.tick();
        assertEquals(3, delivered.size());
        assertEquals("DELIVERED", run(service, sent).get("delivery_state"));
        assertEquals("FAILED", run(service, failed).get("delivery_state"));
        assertEquals("UNKNOWN", run(service, unknown).get("delivery_state"));
        assertEquals("CANCELLED", run(service, stopped).get("delivery_state"));
        db.tx(
                () -> {
                    maintenance(ACTIVITY_CLOCK).clear(sent);
                    return null;
                });
        assertEquals(0, ((Number) service.runs(sent, 5, 0).get("total")).intValue());
        assertEquals("", db.one("select body from outbox where group_id=?", sent).get("body"));
        db.exec("update activity_schedule set next_run_at=? where group_id=?", TICK, sent);
        service.scheduleDue();
        assertEquals(1, count("select count(*) n from activity_run where group_id=?", sent));
        assertEquals(1, count("select count(*) n from outbox where group_id=?", sent));
        db.tx(
                () -> {
                    administration(ACTIVITY_CLOCK).delete(stopped, "stopped", 0);
                    return null;
                });
        assertEquals(0, count("select count(*) n from activity_run where group_id=?", stopped));
        assertEquals(
                0, count("select count(*) n from activity_schedule where group_id=?", stopped));
        new MemoryMaintenance(
                        db,
                        properties(false),
                        mock(MarkdownArchive.class),
                        mock(MemorySearchIndex.class),
                        Clock.offset(ACTIVITY_CLOCK, Duration.ofDays(31)),
                        new RetentionProperties(30, 30, 30))
                .cleanup();
        assertEquals(0, count("select count(*) n from activity_run"));
        assertEquals(0, count("select count(*) n from outbox"));
    }

    @Test
    void boundedSqlAggregatesHundredThousandSyntheticMessages() {
        UUID group = fixture.group("benchmark");
        db.exec(
                """
            with recursive sample(seq) as (select 1 union all select seq+1 from sample where seq<100000)
            insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,received_at,epoch,kind,event_seq)
            select 'sample-'||seq,?,'msg-'||seq,'member-'||(seq%250),'昵称 '||(seq%250),'合成正文',?+seq,?,2,'TEXT',seq from sample
            """,
                group, YESTERDAY, TICK);
        var plan =
                db.jdbc.queryForList(
                        "explain query plan select sender_id,count(*) from group_activity_event where group_id=? and epoch=? and occurred_at>=? and occurred_at<? group by sender_id",
                        group.toString(),
                        2,
                        YESTERDAY.toEpochMilli(),
                        TICK.toEpochMilli());
        assertTrue(
                plan.stream()
                        .anyMatch(row -> row.get("detail").toString().contains("message_window")),
                plan.toString());
        long start = System.nanoTime();
        var result =
                new GroupMembers(db, ACTIVITY_CLOCK).list(group, 7, "", "messages", false, 25, 0);
        long rosterMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(250, ((Number) result.get("total")).intValue());
        assertEquals(400, member(result, 0).messageCount());
        settingsBeforeTick().save(group, true, MORNING, 10, "CONSOLE_ONLY", 0);
        var service = new DailyActivityService(db, ACTIVITY_CLOCK, properties(false));
        start = System.nanoTime();
        service.scheduleDue();
        long rankingMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(10, ((List<?>) run(service, group).get("ranking")).size());
        System.out.printf(
                "Group activity SQLite 100000 rows: roster=%dms ranking=%dms%n",
                rosterMs, rankingMs);
    }

    @Test
    void afterAnOutageOnlyCurrentBusinessDateIsProcessedAndOlderOutboxIsNotReplayed() {
        UUID group = fixture.group("restarted");
        var props = properties(true);
        settingsBeforeTick().save(group, true, MORNING, 10, "GROUP", 0);
        sample(group, "snapshot", "member", "昵称", YESTERDAY, TICK, 2, "TEXT", 1);
        var original = new DailyActivityService(db, ACTIVITY_CLOCK, props);
        original.scheduleDue();
        assertEquals("QUEUED", run(original, group).get("delivery_state"));
        Clock restart = Clock.offset(ACTIVITY_CLOCK, Duration.ofDays(3));
        new DailyActivityService(db, restart, props).scheduleDue();
        assertEquals(2, count("select count(*) n from activity_run"));
        assertEquals(
                1,
                count(
                        "select count(*) n from activity_run where business_date='2026-11-20' and state='NO_DATA'"));
        db.exec("update outbox set next_attempt_at=0");
        MessageTransport transport = mock(MessageTransport.class);
        new OutboxWorker(db, transport, props, restart).tick();
        verifyNoInteractions(transport);
        assertEquals(
                1,
                count(
                        "select count(*) n from activity_run where delivery_state='CANCELLED' and error_code='STALE_RESULT'"));
    }

    private ActivityScheduleService settingsBeforeTick() {
        return new ActivityScheduleService(
                db, Clock.offset(ACTIVITY_CLOCK, Duration.ofMinutes(-20)));
    }

    private GroupAdministration administration(Clock clock) {
        return new GroupAdministration(db, properties(false), mock(MarkdownMemory.class), clock);
    }

    private MemoryMaintenance maintenance(Clock clock) {
        return new MemoryMaintenance(
                db,
                properties(false),
                mock(MarkdownArchive.class),
                mock(MemorySearchIndex.class),
                clock);
    }

    private IngestService ingest(BotProperties props, ReplyPolicyService policies, Clock clock) {
        var replies = new ReplyQueue(db);
        var conversations = new ConversationService(db);
        var commands =
                new CommandHandler(
                        conversations,
                        replies,
                        new DailySummaryService(db, clock),
                        db,
                        policies,
                        clock);
        var admission = new RunAdmission(db, replies, policies);
        return new IngestService(
                new ScopeRegistry(db),
                new MessageJournal(db, props, clock),
                policies,
                conversations,
                commands,
                admission);
    }

    private IngestService.Receipt accept(IngestService ingest, GroupIngestRequest request) {
        return db.tx(() -> ingest.ingest(request));
    }

    private GroupIngestRequest event(
            String channel,
            String account,
            String group,
            String message,
            String sender,
            String text,
            Instant when,
            boolean mention) {
        return new GroupIngestRequest(
                channel, account, group, message, sender, "测试成员", when, text, mention, false);
    }

    private void official(
            OfficialQqEvents adapter,
            String type,
            String key,
            String sender,
            String body,
            Instant at) {
        var payload =
                new ObjectMapper()
                        .valueToTree(
                                Map.of(
                                        "op",
                                        0,
                                        "t",
                                        type,
                                        "d",
                                        Map.of(
                                                "id",
                                                key,
                                                "group_openid",
                                                "qq-room",
                                                "author",
                                                Map.of("member_openid", sender),
                                                "content",
                                                body,
                                                "timestamp",
                                                at.toString())));
        db.tx(
                () -> {
                    adapter.dispatch(payload, "test");
                    return null;
                });
    }

    private BotProperties properties(boolean active) {
        var qq =
                new BotProperties.Qq(
                        true,
                        new BotProperties.OfficialQq(
                                "test",
                                "secret",
                                "http://unused",
                                "http://unused",
                                active,
                                "websocket",
                                "bot-self"));
        return new BotProperties(
                "web-admin",
                "",
                directory,
                false,
                null,
                null,
                qq,
                new BotProperties.Gateway(true, "http://unused", "token", "bridge", ""),
                new BotProperties.Rag(false));
    }

    private UUID otherAccount(String name) {
        UUID id = UUID.randomUUID();
        db.exec(
                "insert into group_config(id,channel,account_id,external_id,name,enabled,epoch) values(?,'QQ','other-app',?,?,true,2)",
                id,
                name,
                name);
        return id;
    }

    private void sample(
            UUID group,
            String key,
            String sender,
            String name,
            Instant at,
            Instant received,
            int epoch,
            String kind,
            int sequence) {
        db.exec(
                "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,received_at,epoch,kind,event_seq) values(?,?,?,?,?,'合成正文',?,?,?,?,?)",
                UUID.randomUUID(),
                group,
                key,
                sender,
                name,
                at,
                received,
                epoch,
                kind,
                sequence);
    }

    private int count(String sql, Object... args) {
        return ((Number) db.one(sql, args).get("n")).intValue();
    }

    private static GroupMembers.Member member(Map<String, Object> result, int index) {
        return (GroupMembers.Member) ((List<?>) result.get("members")).get(index);
    }

    private static Map<?, ?> run(DailyActivityService service, UUID group) {
        return (Map<?, ?>) ((List<?>) service.runs(group, 5, 0).get("runs")).get(0);
    }

    private static int status(Runnable operation) {
        return assertThrows(ResponseStatusException.class, operation::run).getStatusCode().value();
    }
}
