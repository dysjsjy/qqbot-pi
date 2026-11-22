package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.qqbot.channel.*;
import dev.qqbot.delivery.MessageTransport;
import dev.qqbot.memory.*;
import dev.qqbot.message.*;
import dev.qqbot.schedule.DailySummaryService;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class T03HistoryTest {
    @TempDir Path directory;
    private static final Instant NOW = Instant.parse("2026-11-11T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private MemoryV2TestDatabase fixture;
    private UUID groupId;
    private UUID conversationId;
    private ConversationHistory history;

    @BeforeEach
    void prepare() {
        fixture = database();
        groupId = fixture.group("one");
        conversationId = conversation("A", 1);
        history = new ConversationHistory(fixture.db);
    }

    MemoryV2TestDatabase database() {
        return new MemoryV2TestDatabase(directory.resolve("history.db"));
    }

    @AfterEach
    void closeDatabase() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void H01DuplicateIngestAllocatesOneSequenceAndOneRun() {
        var ingest = ingest(properties(false));
        var first = inbound("first", "A", true);
        var receipt = fixture.db.tx(() -> ingest.ingest(first));
        assertFalse(receipt.duplicate());
        for (int attempt = 0; attempt < 3; attempt++) {
            assertTrue(fixture.db.tx(() -> ingest.ingest(first)).duplicate());
        }
        fixture.db.tx(() -> ingest.ingest(inbound("second", "B", false)));
        assertEquals(2, fixture.db.list("select id from group_message").size());
        assertEquals(1, fixture.db.list("select id from agent_run").size());
        assertEquals(
                1L,
                fixture.db
                        .one("select event_seq from group_message where id=?", receipt.messageId())
                        .get("event_seq"));
        assertEquals(
                2L,
                fixture.db
                        .one("select event_seq from group_config where id=?", groupId)
                        .get("event_seq"));
        assertEquals(1L, fixture.db.one("select context_seq from agent_run").get("context_seq"));
    }

    @Test
    void H02OfficialMentionUpgradeKeepsOriginalSequenceAndTimestamp() {
        fixture.db.exec("update group_config set account_id='app' where id=?", groupId);
        var ingest = ingest(properties(true));
        var ordinary =
                new GroupIngestRequest(
                        "QQ",
                        "app",
                        "one",
                        "first",
                        "A",
                        "甲",
                        NOW.minusSeconds(60),
                        "原问题",
                        false,
                        false);
        var first = fixture.db.tx(() -> ingest.ingest(ordinary));
        fixture.db.tx(
                () ->
                        ingest.ingest(
                                new GroupIngestRequest(
                                        "QQ",
                                        "app",
                                        "one",
                                        "second",
                                        "B",
                                        "乙",
                                        NOW.minusSeconds(30),
                                        "插话",
                                        false,
                                        false)));
        var upgraded =
                new GroupIngestRequest(
                        "QQ", "app", "one", "first", "A", "甲", NOW, "原问题", true, false);
        assertFalse(fixture.db.tx(() -> ingest.ingest(upgraded)).duplicate());
        assertTrue(fixture.db.tx(() -> ingest.ingest(upgraded)).duplicate());
        var run =
                fixture.db.one("select context_seq,context_occurred_at,message_id from agent_run");
        assertEquals(first.messageId(), run.get("message_id"));
        assertEquals(1L, run.get("context_seq"));
        assertEquals(Timestamp.from(NOW.minusSeconds(60)), run.get("context_occurred_at"));
        assertEquals(
                2L,
                fixture.db
                        .one("select event_seq from group_config where id=?", groupId)
                        .get("event_seq"));
    }

    @Test
    void H03H04SequenceAndOccurredTimeBothExcludeFutureEvents() {
        UUID past = message("past", "A", "过去", 1, NOW.minusSeconds(600));
        message("future-time", "B", "时间在问题以后", 2, NOW.plusSeconds(60));
        UUID equal = message("equal-time", "B", "同时间但先收到", 9, NOW);
        var request = trigger(10, NOW, null);
        message("late-arrival", "B", "时间早但晚收到", 11, NOW.minusSeconds(3600));
        var result = history.load(request);
        assertEquals(
                List.of(key(past), key(equal)),
                result.stream().map(HistoryEvent::sourceKey).toList());
        assertEquals(List.of(1L, 9L), result.stream().map(HistoryEvent::seq).toList());
    }

    @Test
    void H03EqualTimesUseSequenceEvenWhenUuidOrderIsReversed() {
        UUID first = new UUID(0, 20), second = new UUID(0, 10);
        Instant occurred = NOW.minusSeconds(120);
        fixture.db.exec(
                "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,received_at,epoch,kind,event_seq) values(?,?,'same-time-first','B','乙','先收到',?,?,2,'TEXT',1)",
                first,
                groupId,
                occurred,
                occurred);
        fixture.db.exec(
                "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,received_at,epoch,kind,event_seq) values(?,?,'same-time-second','B','乙','后收到',?,?,2,'TEXT',2)",
                second,
                groupId,
                occurred,
                occurred);
        fixture.db.exec("update group_config set event_seq=2 where id=?", groupId);
        assertTrue(first.compareTo(second) > 0);
        var result = history.load(trigger(3, NOW, null));
        assertEquals(
                List.of(key(first), key(second)),
                result.stream().map(HistoryEvent::sourceKey).toList());
        assertEquals(List.of(1L, 2L), result.stream().map(HistoryEvent::seq).toList());
        assertEquals(List.of("先收到", "后收到"), result.stream().map(HistoryEvent::body).toList());
    }

    @Test
    void H05H06OnlySucceededSentSegmentsWithRealDeliveryMetadataAreHistory() {
        UUID question = message("q", "A", "问题", 1, NOW.minusSeconds(600));
        UUID run = run(question, conversationId, 1, "SUCCEEDED");
        UUID delivered = outbox(run, 2L, "已发段", "SENT", NOW.minusSeconds(500), "platform");
        outbox(run, null, "未知段", "UNKNOWN", null, null);
        outbox(run, null, "待发段", "PENDING", null, null);
        outbox(run, null, "旧SENT无投递元数据", "SENT", null, null);
        UUID failedQuestion = message("failed-q", "B", "另一个问题", 3, NOW.minusSeconds(400));
        UUID failed = run(failedQuestion, conversation("B", 1), 1, "FAILED");
        outbox(failed, 4L, "失败提示不能当回答", "SENT", NOW.minusSeconds(300), "failed-platform");
        var result = history.load(trigger(10, NOW, null));
        assertEquals(
                List.of("已发段"),
                result.stream()
                        .filter(event -> event.role() == Role.ASSISTANT)
                        .map(HistoryEvent::body)
                        .toList());
        var answer =
                result.stream()
                        .filter(event -> event.sourceKey().equals("outbox:" + delivered))
                        .findFirst()
                        .orElseThrow();
        assertEquals(key(question), answer.replyToKey());
        assertEquals(1L, answer.generation());
        assertTrue(result.stream().noneMatch(event -> event.body().contains("完整但未全部发出的文本")));
    }

    @Test
    void H06RepeatedAcknowledgementDoesNotSendOrAllocateAgain() {
        UUID question = message("q", "A", "问题", 1, NOW.minusSeconds(600));
        UUID run = run(question, conversationId, 1, "SUCCEEDED");
        UUID outboxId = outbox(run, null, "实际投递", "PENDING", null, null);
        var sent = new AtomicInteger();
        MessageTransport transport =
                message -> {
                    sent.incrementAndGet();
                    return new MessageTransport.Delivery(
                            MessageTransport.State.ACCEPTED, "sent-id", null);
                };
        var worker = new OutboxWorker(fixture.db, transport, null, CLOCK);
        var claimed = worker.claim().orElseThrow();
        assertEquals(outboxId, claimed.id());
        worker.send(claimed);
        var first =
                fixture.db.one(
                        "select event_seq,delivered_at,state from outbox where id=?", outboxId);
        new OutboxWorker(
                        fixture.db,
                        transport,
                        null,
                        Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC))
                .send(claimed);
        assertEquals(
                first,
                fixture.db.one(
                        "select event_seq,delivered_at,state from outbox where id=?", outboxId));
        assertEquals(1, sent.get());
        assertEquals(2L, first.get("event_seq"));
        assertEquals(Timestamp.from(NOW), first.get("delivered_at"));
        assertEquals(
                2L,
                fixture.db
                        .one("select event_seq from group_config where id=?", groupId)
                        .get("event_seq"));
    }

    @Test
    void H07FourOwnRunsIncludeEverySentPartOutsideRecentEighty() {
        var expected = new ArrayList<String>();
        long sequence = 0;
        for (int index = 0; index < 4; index++) {
            UUID question =
                    message("own" + index, "A", "本人问题" + index, ++sequence, NOW.minusSeconds(3600));
            UUID run = run(question, conversationId, 1, "SUCCEEDED");
            expected.add(key(question));
            for (int part = 0; part < 2; part++) {
                expected.add(
                        "outbox:"
                                + outbox(
                                        run,
                                        ++sequence,
                                        "本人分段" + index + "/" + part,
                                        "SENT",
                                        NOW.minusSeconds(3500),
                                        "p" + index + "-" + part));
            }
        }
        for (int index = 0; index < 80; index++) {
            message("other" + index, "B", "插话" + index, ++sequence, NOW.minusSeconds(60));
        }
        var result = history.load(trigger(++sequence, NOW, null));
        assertEquals(92, result.size());
        assertTrue(result.stream().map(HistoryEvent::sourceKey).toList().containsAll(expected));
        assertEquals(8, result.stream().filter(event -> event.role() == Role.ASSISTANT).count());
        assertEquals(
                result.stream().map(HistoryEvent::seq).sorted().toList(),
                result.stream().map(HistoryEvent::seq).toList());
    }

    @Test
    void H07CapacityRetainsQuoteAndOwnRunsBeforeNewestRecentEvents() {
        var expected = new ArrayList<Long>();
        for (long sequence = 1; sequence <= 29; sequence++) {
            expected.add(sequence);
        }
        for (long sequence = 39; sequence <= 109; sequence++) {
            expected.add(sequence);
        }
        assertCapacity(6, expected);
    }

    @Test
    void H07CapacityFillsNewestOwnRunsBeforePartialOldestRun() {
        var expected = new ArrayList<Long>();
        for (long sequence = 1; sequence <= 18; sequence++) {
            expected.add(sequence);
        }
        for (long sequence = 28; sequence <= 109; sequence++) {
            expected.add(sequence);
        }
        assertCapacity(26, expected);
    }

    private void assertCapacity(int parts, List<Long> expected) {
        var keys = new LinkedHashMap<Long, String>();
        var roles = new LinkedHashMap<Long, Role>();
        var bodies = new LinkedHashMap<Long, String>();
        long sequence = 0;
        for (int round = 0; round < 4; round++) {
            long questionSequence = ++sequence;
            String questionBody = "own-question-" + round;
            UUID question =
                    message(
                            "capacity-own-" + round,
                            "A",
                            questionBody,
                            questionSequence,
                            NOW.minusSeconds(3600));
            UUID run = run(question, conversationId, 1, "SUCCEEDED");
            keys.put(questionSequence, key(question));
            roles.put(questionSequence, Role.USER);
            bodies.put(questionSequence, questionBody);
            for (int part = 0; part < parts; part++) {
                long answerSequence = ++sequence;
                String body = "own-answer-" + round + "-" + part;
                UUID answer =
                        outbox(
                                run,
                                answerSequence,
                                body,
                                "SENT",
                                NOW.minusSeconds(3500),
                                "capacity-" + round + "-" + part);
                keys.put(answerSequence, "outbox:" + answer);
                roles.put(answerSequence, Role.ASSISTANT);
                bodies.put(answerSequence, body);
            }
        }
        long quoteSequence = ++sequence;
        UUID quoted =
                message(
                        "capacity-quote",
                        "B",
                        "explicit-quote",
                        quoteSequence,
                        NOW.minusSeconds(3000));
        keys.put(quoteSequence, key(quoted));
        roles.put(quoteSequence, Role.USER);
        bodies.put(quoteSequence, "explicit-quote");
        for (int index = 0; index < 80; index++) {
            long recentSequence = ++sequence;
            String body = "recent-" + index;
            UUID recent =
                    message(
                            "capacity-recent-" + index,
                            "B",
                            body,
                            recentSequence,
                            NOW.minusSeconds(60));
            keys.put(recentSequence, key(recent));
            roles.put(recentSequence, Role.USER);
            bodies.put(recentSequence, body);
        }
        var result = history.load(trigger(++sequence, NOW, "capacity-quote"));
        assertEquals(100, result.size());
        assertEquals(expected, result.stream().map(HistoryEvent::seq).toList());
        for (var event : result) {
            assertEquals(keys.get(event.seq()), event.sourceKey());
            assertEquals(roles.get(event.seq()), event.role());
            assertEquals(bodies.get(event.seq()), event.body());
            assertEquals(event.seq() == quoteSequence, event.explicitlyReferenced());
        }
    }

    @Test
    void H08ReferencesResolveLocalOriginalAndBotPlatformIdButNotOtherGroup() {
        UUID quoted = message("shared-key", "B", "本群引用", 1, NOW.minusSeconds(600));
        UUID otherGroup = fixture.group("two");
        fixture.message(otherGroup, "shared-key", "B", "其他群秘密", 1, NOW.minusSeconds(600));
        fixture.message(otherGroup, "only-other", "B", "跨群ID", 2, NOW.minusSeconds(600));
        UUID question = message("bot-q", "A", "机器人问题", 2, NOW.minusSeconds(500));
        UUID bot =
                outbox(
                        run(question, conversationId, 1, "SUCCEEDED"),
                        3L,
                        "机器人引用目标",
                        "SENT",
                        NOW.minusSeconds(400),
                        "bot-platform");
        var userResult = history.load(trigger(10, NOW, "shared-key"));
        assertTrue(
                userResult.stream()
                        .anyMatch(
                                event ->
                                        event.sourceKey().equals(key(quoted))
                                                && event.explicitlyReferenced()));
        assertTrue(userResult.stream().noneMatch(event -> event.body().contains("其他群秘密")));
        var botResult = history.load(trigger(11, NOW, "bot-platform"));
        assertTrue(
                botResult.stream()
                        .anyMatch(
                                event ->
                                        event.sourceKey().equals("outbox:" + bot)
                                                && event.explicitlyReferenced()));
        assertTrue(
                history.load(trigger(12, NOW, "only-other")).stream()
                        .noneMatch(HistoryEvent::explicitlyReferenced));
    }

    @Test
    void H08QuotedMessageOutsideEightyActualEventsIsStillSelected() {
        UUID quoted = message("outside-recent", "B", "窗口外引用正文", 1, NOW.minusSeconds(600));
        for (int position = 2; position <= 81; position++) {
            message("filler-" + position, "B", "真实插话 " + position, position, NOW.minusSeconds(60));
        }
        assertFalse(
                history.load(trigger(82, NOW, null)).stream()
                        .anyMatch(event -> event.sourceKey().equals(key(quoted))));
        var referenced = history.load(trigger(83, NOW, "outside-recent"));
        assertTrue(
                referenced.stream()
                        .anyMatch(
                                event ->
                                        event.sourceKey().equals(key(quoted))
                                                && event.explicitlyReferenced()));
        assertEquals(81, referenced.size());
    }

    @Test
    void H09NamesAndPrivateScopesRemainIndependent() {
        UUID first = message("name-a", "A", "偏好一", 1, NOW.minusSeconds(600));
        UUID second = message("name-b", "B", "偏好二", 2, NOW.minusSeconds(500));
        fixture.db.exec(
                "update group_message set sender_name='同名' where id in (?,?)", first, second);
        UUID renamed = message("renamed", "A", "我改名了", 3, NOW.minusSeconds(400));
        fixture.db.exec("update group_message set sender_name='新昵称' where id=?", renamed);
        UUID privateGroup = fixture.group("private");
        fixture.db.exec(
                "update group_config set chat_type='PRIVATE',target_id='A' where id=?",
                privateGroup);
        UUID privateMessage =
                fixture.message(privateGroup, "private", "A", "只在私聊", 1, NOW.minusSeconds(300));
        var result = history.load(trigger(10, NOW, null));
        assertEquals(List.of("A", "B", "A"), result.stream().map(HistoryEvent::senderId).toList());
        assertEquals(
                List.of("同名", "同名", "新昵称"), result.stream().map(HistoryEvent::senderName).toList());
        var privateRequest =
                new ContextRequest(
                        new MemoryScope(privateGroup, 2, null, 2, NOW, NOW),
                        UUID.randomUUID(),
                        1,
                        "A",
                        "继续",
                        QueryMode.CHAT);
        assertEquals(
                List.of(key(privateMessage)),
                history.load(privateRequest).stream().map(HistoryEvent::sourceKey).toList());
    }

    @Test
    void H10RetentionEpochAndPersonalGenerationUseExactBoundaries() {
        UUID boundary = message("boundary", "A", "三十天整", 1, NOW.minus(Duration.ofDays(30)));
        message("expired", "A", "超过保留期", 2, NOW.minus(Duration.ofDays(30)).minusMillis(1));
        UUID oldEpoch = message("old-epoch", "A", "旧epoch", 3, NOW.minusSeconds(60));
        fixture.db.exec("update group_message set epoch=1 where id=?", oldEpoch);
        assertEquals(
                List.of(key(boundary)),
                history.load(trigger(10, NOW, null)).stream()
                        .map(HistoryEvent::sourceKey)
                        .toList());
        UUID oldQuestion =
                message("old-generation", "A", "旧generation问题", 11, NOW.minusSeconds(3600));
        UUID oldAnswer =
                outbox(
                        run(oldQuestion, conversationId, 0, "SUCCEEDED"),
                        12L,
                        "旧generation答复",
                        "SENT",
                        NOW.minusSeconds(3500),
                        "old-answer");
        UUID currentQuestion =
                message(
                        "current-generation",
                        "A",
                        "当前generation问题",
                        13,
                        NOW.minus(Duration.ofHours(24)));
        UUID currentAnswer =
                outbox(
                        run(currentQuestion, conversationId, 1, "SUCCEEDED"),
                        14L,
                        "恰好24小时答复",
                        "SENT",
                        NOW.minus(Duration.ofHours(24)),
                        "current-answer");
        UUID tooOldQuestion =
                message(
                        "past-personal-window",
                        "A",
                        "更早的个人问答",
                        15,
                        NOW.minus(Duration.ofHours(24)).minusMillis(1));
        UUID tooOldAnswer =
                outbox(
                        run(tooOldQuestion, conversationId, 1, "SUCCEEDED"),
                        16L,
                        "24小时外答复",
                        "SENT",
                        NOW.minus(Duration.ofHours(24)).minusMillis(1),
                        "too-old-answer");
        for (int index = 0; index < 90; index++) {
            message("noise" + index, "B", "背景" + index, 20 + index, NOW.minusSeconds(30));
        }
        var request = trigger(200, NOW, null);
        var selected = history.load(request).stream().map(HistoryEvent::sourceKey).toList();
        assertTrue(selected.containsAll(List.of(key(currentQuestion), "outbox:" + currentAnswer)));
        assertFalse(selected.contains(key(oldQuestion)));
        assertFalse(selected.contains("outbox:" + oldAnswer));
        assertFalse(selected.contains(key(tooOldQuestion)));
        assertFalse(selected.contains("outbox:" + tooOldAnswer));
        fixture.db.exec("update group_config set epoch=3 where id=?", groupId);
        assertTrue(history.load(request).isEmpty());
    }

    private UUID message(String key, String sender, String body, long sequence, Instant time) {
        return fixture.message(groupId, key, sender, body, sequence, time);
    }

    private String key(UUID id) {
        return "message:" + id + ":0";
    }

    private ContextRequest trigger(long sequence, Instant time, String reply) {
        UUID id =
                message("trigger" + sequence, "A", "current-question-" + sequence, sequence, time);
        fixture.db.exec("update group_message set reply_to_key=? where id=?", reply, id);
        return new ContextRequest(
                new MemoryScope(groupId, 2, id, sequence, time, NOW),
                conversationId,
                1,
                "A",
                "current-question-" + sequence,
                QueryMode.CHAT);
    }

    private UUID conversation(String sender, long generation) {
        UUID id = UUID.randomUUID();
        fixture.db.exec(
                "insert into conversation(id,group_id,sender_id,generation) values(?,?,?,?)",
                id,
                groupId,
                sender,
                generation);
        return id;
    }

    private UUID run(UUID question, UUID conversation, long generation, String state) {
        UUID id = UUID.randomUUID();
        var origin =
                fixture.db.one(
                        "select event_seq,occurred_at from group_message where id=?", question);
        fixture.db.exec(
                "insert into agent_run(id,group_id,message_id,conversation_id,generation,epoch,kind,prompt,state,final_text,context_seq,context_occurred_at) values(?,?,?,?,?,2,'CHAT','问题',?,'完整但未全部发出的文本',?,?)",
                id,
                groupId,
                question,
                conversation,
                generation,
                state,
                origin.get("event_seq"),
                origin.get("occurred_at"));
        return id;
    }

    private UUID outbox(
            UUID run,
            Long sequence,
            String body,
            String state,
            Instant delivered,
            String platform) {
        var task =
                fixture.db.one(
                        "select conversation_id,generation,message_id from agent_run where id=?",
                        run);
        int part =
                ((Number)
                                fixture.db
                                        .one(
                                                "select count(*) n from outbox where agent_run_id=?",
                                                run)
                                        .get("n"))
                        .intValue();
        UUID id = UUID.randomUUID();
        fixture.db.exec(
                "insert into outbox(id,group_id,result_key,agent_run_id,conversation_id,generation,epoch,part_index,body,state,event_seq,delivered_at,platform_id,reply_to_message_id) values(?,?,?,?,?,?,2,?,?,?,?,?,?,?)",
                id,
                groupId,
                run.toString(),
                run,
                task.get("conversation_id"),
                task.get("generation"),
                part,
                body,
                state,
                sequence,
                delivered,
                platform,
                task.get("message_id"));
        if (sequence != null) {
            fixture.db.exec(
                    "update group_config set event_seq=max(event_seq,?) where id=?",
                    sequence,
                    groupId);
        }
        return id;
    }

    private GroupIngestRequest inbound(String key, String sender, boolean mention) {
        return new GroupIngestRequest(
                "QQ", "test", "one", key, sender, sender, NOW, "你好", mention, false);
    }

    private BotProperties properties(boolean official) {
        var gateway =
                new BotProperties.Qq(
                        false,
                        new BotProperties.OfficialQq(official ? "app" : "test", "", "", "", false));
        return new BotProperties(
                "admin",
                "",
                directory,
                false,
                new BotProperties.Pi("node", "unused", "local", "model", "KEY", "", 5, ""),
                null,
                gateway,
                new BotProperties.Gateway(false, "", "", "", ""),
                new BotProperties.Rag(false));
    }

    private IngestService ingest(BotProperties properties) {
        var conversations = new ConversationService(fixture.db);
        var replies = new ReplyQueue(fixture.db);
        var policies = new ReplyPolicyService(fixture.db);
        var summaries = new DailySummaryService(fixture.db, CLOCK, properties);
        return new IngestService(
                new ScopeRegistry(fixture.db),
                new MessageJournal(fixture.db, properties, CLOCK),
                policies,
                conversations,
                new CommandHandler(conversations, replies, summaries, fixture.db),
                new RunAdmission(fixture.db, replies, policies));
    }
}
