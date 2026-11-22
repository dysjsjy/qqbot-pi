/**
 * [INPUT]: 依赖 SQLite 队列、Clock、平台配置与 MessageTransport。
 * [OUTPUT]: 提供有类型的认领结果、顺序投递和各类运行的回执汇总。
 * [POS]: 出站边界。在发送事务内复核群、会话及榜单权限并记录回执。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.channel;

import static dev.qqbot.delivery.MessageTransport.*;

import dev.qqbot.BotProperties;
import dev.qqbot.delivery.MessageTransport;
import dev.qqbot.message.Group;
import dev.qqbot.persistence.Db;
import dev.qqbot.trace.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final int MAX_BATCH_SIZE = 20;
    private static final int MAX_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MILLIS = 5_000;
    private static final Duration ACTIVITY_DELIVERY_WINDOW = Duration.ofDays(1);
    private static final String ACTIVITY_PREFIX = "activity:";
    private static final String SEND_RESULT_UNKNOWN = "SEND_RESULT_UNKNOWN";
    private final Db db;
    private final MessageTransport sender;
    private final BotProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Clock clock;
    private TraceSink traces;

    /** 配置可选的投递跟踪记录器。 */
    @Autowired(required = false)
    public void setTraces(TraceSink traces) {
        this.traces = traces;
    }

    /** 使用 UTC 系统时钟创建出站轮询器。 */
    public OutboxWorker(Db db, MessageTransport sender, BotProperties properties) {
        this(db, sender, properties, Clock.systemUTC());
    }

    /** 使用给定时钟计算跟踪等待及投递时间。 */
    @Autowired
    public OutboxWorker(Db db, MessageTransport sender, BotProperties properties, Clock clock) {
        this.db = db;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    /** 认领并发送一批可投递分段，同实例内不重叠执行。 */
    @Scheduled(fixedDelayString = "${bot.worker.poll-ms:200}")
    public void tick() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            cancelBlockedParts();
            deliverBatch();
            reconcile();
        } finally {
            running.set(false);
        }
    }

    private void deliverBatch() {
        for (int index = 0; index < MAX_BATCH_SIZE; index++) {
            var next = claim();
            if (next.isEmpty()) {
                return;
            }
            send(next.orElseThrow());
        }
    }

    /** 原子认领首个合格分段并增加尝试次数。队列暂无可发送分段时返回空值。 */
    public Optional<OutboxAttempt> claim() {
        // SIDE EFFECT: 原子认领，确保两个轮询不会取得同一待发分段。
        return db.find(
                """
                update outbox set state='SENDING',attempts=attempts+1 where id=(
                  select o.id from outbox o join group_config g on g.id=o.group_id
                  where o.state='PENDING' and o.next_attempt_at<=cast(unixepoch('subsec')*1000 as integer)
                    and g.enabled and g.reply_enabled and g.epoch=o.epoch
                    and not exists(select 1 from outbox p where p.result_key=o.result_key
                      and p.part_index<o.part_index and p.state<>'SENT')
                  order by o.created_at,o.part_index limit 1) and state='PENDING' returning *
                """,
                OutboxWorker::readAttempt);
    }

    private static OutboxAttempt readAttempt(ResultSet row, int rowNumber) throws SQLException {
        UUID agentRun = readId(row, "agent_run_id");
        long generation = row.getLong("generation");
        Long storedGeneration = row.wasNull() ? null : generation;
        return new OutboxAttempt(
                readId(row, "id"),
                new OutboxAttempt.Scope(
                        readId(row, "group_id"),
                        row.getLong("epoch"),
                        readId(row, "conversation_id"),
                        storedGeneration),
                new OutboxAttempt.Payload(
                        row.getString("result_key"),
                        row.getString("body"),
                        readId(row, "reply_to_message_id"),
                        agentRun == null ? readId(row, "daily_run_id") : agentRun),
                new OutboxAttempt.Order(
                        row.getInt("part_index"),
                        row.getInt("attempts"),
                        Instant.ofEpochMilli(row.getLong("created_at"))));
    }

    private static UUID readId(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    /** 投递已认领且仍有效的分段。`attempt` 必须来自本队列。 */
    public void send(OutboxAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        startTrace(attempt);
        String resultCode = null;
        try {
            db.tx(() -> sendAndPublish(attempt));
        } catch (RuntimeException failure) {
            resultCode = SEND_RESULT_UNKNOWN;
            markUnknown(attempt, failure);
        } finally {
            Trace.end(resultCode == null ? Trace.OK : Trace.ERROR, resultCode);
        }
    }

    private void startTrace(OutboxAttempt attempt) {
        UUID tracedRun = attempt.payload().tracedRun();
        Trace.begin(traces, Trace.DELIVERY, "delivery.send", tracedRun, attempt.scope().groupId());
        var order = attempt.order();
        Trace.attr("outboxWaitMs", Math.max(0, clock.millis() - order.createdAt().toEpochMilli()));
        Trace.attr("partIndex", order.partIndex());
        Trace.attr("attempts", order.attempts());
    }

    private Void sendAndPublish(OutboxAttempt attempt) {
        if (!isSending(attempt.id())) {
            return null;
        }
        Group group = loadGroup(attempt.scope().groupId()).orElse(null);
        if (!canDeliver(attempt, group)) {
            cancelStale(attempt.id());
            return null;
        }
        Message message = prepareMessage(attempt, group);
        // SIDE EFFECT: 平台发送不可回滚。SQLite 回执失败时标记为 UNKNOWN。
        Delivery receipt = sendViaTransport(message);
        String state = stateAfterDelivery(receipt.state(), attempt.order().attempts());
        Trace.attr("state", state);
        publishReceipt(attempt, receipt, state);
        logReceipt(attempt, receipt, state);
        return null;
    }

    private boolean isSending(UUID id) {
        return db.find("select state from outbox where id=?", (row, index) -> row.getString(1), id)
                .filter("SENDING"::equals)
                .isPresent();
    }

    private Optional<Group> loadGroup(UUID groupId) {
        return Optional.ofNullable(db.one("select * from group_config where id=?", groupId))
                .map(Group::from);
    }

    private boolean canDeliver(OutboxAttempt attempt, Group group) {
        return group != null
                && group.enabled()
                && group.replyEnabled()
                && group.epoch() == attempt.scope().epoch()
                && isAccountEnabled(group)
                && canPublishActivity(attempt)
                && isCurrentGeneration(attempt.scope());
    }

    private boolean isAccountEnabled(Group group) {
        if (properties == null) {
            return true;
        }
        var gateway = properties.gateway(group.channel());
        return gateway.enabled() && gateway.accountId().equals(group.accountId());
    }

    private boolean canPublishActivity(OutboxAttempt attempt) {
        String key = attempt.payload().resultKey();
        if (!key.startsWith(ACTIVITY_PREFIX)) {
            return true;
        }
        return db.find(
                        """
                        select 1 from activity_run r join activity_schedule s on s.group_id=r.group_id
                        where r.id=? and r.group_id=? and r.epoch=? and r.delivery_state='QUEUED'
                          and r.ends_at>? and s.enabled and s.publish_mode='GROUP'
                        """,
                        (row, index) -> row.getInt(1),
                        key.substring(ACTIVITY_PREFIX.length()),
                        attempt.scope().groupId(),
                        attempt.scope().epoch(),
                        clock.instant().minus(ACTIVITY_DELIVERY_WINDOW))
                .isPresent();
    }

    private boolean isCurrentGeneration(OutboxAttempt.Scope scope) {
        if (scope.conversationId() == null) {
            return true;
        }
        return db.find(
                        "select generation from conversation where id=?",
                        (row, index) -> row.getLong(1),
                        scope.conversationId())
                .filter(generation -> generation.equals(scope.generation()))
                .isPresent();
    }

    private void cancelStale(UUID id) {
        db.exec("update outbox set state='CANCELLED',error_code='STALE_RESULT' where id=?", id);
    }

    private Message prepareMessage(OutboxAttempt attempt, Group group) {
        String target = group.privateChat() ? group.targetId() : group.externalId();
        return new Message(
                group.channel(),
                target,
                group.privateChat(),
                attempt.payload().text(),
                attempt.id().toString(),
                loadReply(attempt));
    }

    private Reply loadReply(OutboxAttempt attempt) {
        UUID origin = attempt.payload().replyToMessageId();
        if (origin == null) {
            return null;
        }
        int sequence = attempt.order().partIndex() + 1;
        return db.find(
                        "select message_key,occurred_at from group_message where id=? and group_id=?",
                        (row, index) -> readReply(row, sequence),
                        origin,
                        attempt.scope().groupId())
                .orElseGet(() -> new Reply(null, null, sequence));
    }

    private static Reply readReply(java.sql.ResultSet row, int sequence)
            throws java.sql.SQLException {
        return new Reply(row.getString(1), Instant.ofEpochMilli(row.getLong(2)), sequence);
    }

    private Delivery sendViaTransport(Message message) {
        try (var span = Trace.span("delivery.transport")) {
            return sender.send(message);
        }
    }

    static String stateAfterDelivery(State state, int attempts) {
        return switch (state) {
            case ACCEPTED -> "SENT";
            case RETRYABLE -> attempts < MAX_ATTEMPTS ? "PENDING" : "FAILED";
            case FAILED -> "FAILED";
            case UNKNOWN -> "UNKNOWN";
        };
    }

    private void publishReceipt(OutboxAttempt attempt, Delivery receipt, String state) {
        if (receipt.state() == State.ACCEPTED) {
            publishSent(attempt, receipt);
            return;
        }
        db.exec(
                """
                update outbox set state=?,platform_id=?,error_code=?,
                  next_attempt_at=cast(unixepoch('subsec')*1000 as integer)+?
                where id=? and state='SENDING'
                """,
                state,
                receipt.platformId(),
                receipt.code(),
                RETRY_DELAY_MILLIS,
                attempt.id());
    }

    private void publishSent(OutboxAttempt attempt, Delivery receipt) {
        long sequence =
                db.find(
                                "update group_config set event_seq=event_seq+1 where id=? returning event_seq",
                                (row, index) -> row.getLong(1),
                                attempt.scope().groupId())
                        .orElseThrow();
        db.exec(
                """
                update outbox set state='SENT',platform_id=?,error_code=?,event_seq=?,delivered_at=?
                where id=? and state='SENDING'
                """,
                receipt.platformId(),
                receipt.code(),
                sequence,
                clock.instant(),
                attempt.id());
    }

    private void logReceipt(OutboxAttempt attempt, Delivery receipt, String state) {
        if (receipt.code() == null) {
            log.info("MESSAGE_SENT outbox={} group={}", attempt.id(), attempt.scope().groupId());
            return;
        }
        log.warn(
                "MESSAGE_DELIVERY outbox={} group={} state={} code={}",
                attempt.id(),
                attempt.scope().groupId(),
                state,
                receipt.code());
    }

    private void markUnknown(OutboxAttempt attempt, RuntimeException failure) {
        log.warn(
                "SEND_RESULT_UNKNOWN outbox={} exception={}",
                attempt.id(),
                failure.getClass().getSimpleName());
        db.exec(
                "update outbox set state='UNKNOWN',error_code=? where id=? and state='SENDING'",
                SEND_RESULT_UNKNOWN,
                attempt.id());
    }

    /** 将分段回执分别汇总给日报和活跃榜。 */
    public void reconcile() {
        reconcileDaily();
        reconcileActivity();
    }

    public record OutboxAttempt(UUID id, Scope scope, Payload payload, Order order) {
        public record Scope(UUID groupId, long epoch, UUID conversationId, Long generation) {}

        public record Payload(
                String resultKey, String text, UUID replyToMessageId, UUID tracedRun) {}

        public record Order(int partIndex, int attempts, Instant createdAt) {}
    }

    private void cancelBlockedParts() {
        db.exec(
                """
                update outbox as o set state='CANCELLED',error_code='PREVIOUS_PART_FAILED'
                where o.state='PENDING' and exists(select 1 from outbox p
                  where p.result_key=o.result_key and p.part_index<o.part_index
                    and p.state in ('UNKNOWN','FAILED','CANCELLED'))
                """);
    }

    private void reconcileDaily() {
        db.exec(
                """
                update daily_run as r set state=case
                when exists(select 1 from outbox o where o.daily_run_id=r.id and o.state='UNKNOWN')
                  then 'DELIVERY_UNKNOWN'
                when exists(select 1 from outbox o where o.daily_run_id=r.id and o.state='FAILED')
                  then 'DELIVERY_FAILED'
                when not exists(select 1 from outbox o where o.daily_run_id=r.id and o.state<>'SENT')
                  then 'DELIVERED'
                else r.state end,
                error_code=coalesce((select o.error_code from outbox o where o.daily_run_id=r.id
                  and o.state in ('UNKNOWN','FAILED') order by o.part_index limit 1),r.error_code)
                where r.state='GENERATED' and exists(select 1 from outbox o where o.daily_run_id=r.id)
                """);
    }

    private void reconcileActivity() {
        db.exec(
                """
                update activity_run as r set delivery_state=case
                when exists(select 1 from outbox o where o.result_key='activity:'||r.id and o.state='UNKNOWN')
                  then 'UNKNOWN'
                when exists(select 1 from outbox o where o.result_key='activity:'||r.id and o.state='FAILED')
                  then 'FAILED'
                when exists(select 1 from outbox o where o.result_key='activity:'||r.id and o.state='CANCELLED')
                  then 'CANCELLED'
                when not exists(select 1 from outbox o where o.result_key='activity:'||r.id and o.state<>'SENT')
                  then 'DELIVERED'
                else r.delivery_state end,
                error_code=coalesce((select o.error_code from outbox o where o.result_key='activity:'||r.id
                  and o.state in ('UNKNOWN','FAILED','CANCELLED')
                  order by case o.state when 'UNKNOWN' then 0 when 'FAILED' then 1 else 2 end,
                    o.part_index limit 1),r.error_code)
                where r.delivery_state='QUEUED' and exists(select 1 from outbox o where o.result_key='activity:'||r.id)
                """);
    }
}
