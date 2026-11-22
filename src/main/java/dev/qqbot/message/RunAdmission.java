/**
 * [INPUT]: 依赖消息路由、群回复策略、会话代际和有类型的 SQLite 查询。
 * [OUTPUT]: 检查队列容量，冻结来源并提交生成任务。
 * [POS]: 入站事务中的模型准入；只有普通消息成功入队才消费能量。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.message;

import dev.qqbot.message.ConversationService.Conversation;
import dev.qqbot.message.IngestService.MessageRoute;
import dev.qqbot.persistence.Db;
import dev.qqbot.trace.Trace;
import dev.qqbot.trace.TraceSink;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RunAdmission {
    private static final int MAX_GLOBAL_QUEUE = 20;
    private static final int MAX_CONVERSATION_QUEUE = 5;
    private static final int MAX_RECENT_RUNS = 6;
    private static final long RATE_WINDOW_MILLIS = 60_000;
    private static final String QUEUE_LOAD =
            """
            select
                (select count(*) from agent_run where state='QUEUED') as queued_global,
                (select count(*) from agent_run where state='QUEUED' and conversation_id=?) as queued_conversation,
                (select count(*) from agent_run where conversation_id=?
                  and created_at>(cast(unixepoch('subsec')*1000 as integer) - ?)) as recent_conversation
            """;
    private static final String ADMISSION_SOURCE =
            """
            select event_seq, occurred_at from group_message
            where id=? and group_id=? and epoch=? and event_seq is not null
            """;
    private static final String INSERT_RUN =
            """
            insert into agent_run(id,group_id,message_id,conversation_id,generation,epoch,kind,prompt,state,
                                  context_seq,context_occurred_at)
            values(?,?,?,?,?,?,?,?,'QUEUED',?,?)
            """;

    private final Db db;
    private final ReplyQueue replies;
    private final ReplyPolicyService policies;
    private TraceSink traceSink;

    /** 创建使用同一入站事务的容量、来源与能量准入服务。 */
    public RunAdmission(Db db, ReplyQueue replies, ReplyPolicyService policies) {
        this.db = db;
        this.replies = replies;
        this.policies = policies;
    }

    /** 设置可选诊断输出；测试可不提供诊断接收器。 */
    @Autowired(required = false)
    public void setTraces(TraceSink traceSink) {
        this.traceSink = traceSink;
    }

    TraceSink traces() {
        return traceSink;
    }

    void enqueue(MessageRoute route, Conversation conversation) {
        QueueLoad load = readQueueLoad(conversation.id());
        if (load.isFull()) {
            enqueueBusyNotice(route);
            return;
        }
        Source source = requireSource(route);
        QueuedRun run = new QueuedRun(UUID.randomUUID(), route, conversation, source);
        // SIDE EFFECT: 入队与能量扣减由入站事务一起提交或回滚。
        insertRun(run);
        Trace.identify(run.id(), route.group().id());
        if (route.isOrdinaryGroupText()) {
            policies.consume(route.group().id());
        }
    }

    private QueueLoad readQueueLoad(UUID conversationId) {
        return db.find(
                        QUEUE_LOAD,
                        RunAdmission::readQueueLoad,
                        conversationId,
                        conversationId,
                        RATE_WINDOW_MILLIS)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Missing queue load: conversationId=" + conversationId));
    }

    private static QueueLoad readQueueLoad(java.sql.ResultSet row, int rowNumber)
            throws java.sql.SQLException {
        return new QueueLoad(
                row.getLong("queued_global"),
                row.getLong("queued_conversation"),
                row.getLong("recent_conversation"));
    }

    private Source requireSource(MessageRoute route) {
        Group group = route.group();
        return db.find(
                        ADMISSION_SOURCE,
                        RunAdmission::readSource,
                        route.messageId(),
                        group.id(),
                        group.epoch())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "SOURCE_MISSING: messageId=" + route.messageId()));
    }

    private static Source readSource(java.sql.ResultSet row, int rowNumber)
            throws java.sql.SQLException {
        return new Source(
                row.getLong("event_seq"), Instant.ofEpochMilli(row.getLong("occurred_at")));
    }

    private void enqueueBusyNotice(MessageRoute route) {
        UUID messageId = route.messageId();
        // SIDE EFFECT: 满载时只保存提示，不增加生成任务或消耗能量。
        replies.enqueue(route.group(), messageId, "cmd:" + messageId, "请求较多，请稍后再试；群消息仍已保存。");
    }

    private void insertRun(QueuedRun run) {
        MessageRoute route = run.route();
        Group group = route.group();
        Conversation conversation = run.conversation();
        Source source = run.source();
        db.exec(
                INSERT_RUN,
                run.id(),
                group.id(),
                route.messageId(),
                conversation.id(),
                conversation.generation(),
                group.epoch(),
                route.isHistoryQuery() ? "MEMORY" : "CHAT",
                route.prompt(),
                source.sequence(),
                source.occurredAt());
    }

    private record QueueLoad(long global, long conversation, long recent) {
        boolean isFull() {
            return global >= MAX_GLOBAL_QUEUE
                    || conversation >= MAX_CONVERSATION_QUEUE
                    || recent >= MAX_RECENT_RUNS;
        }
    }

    private record Source(long sequence, Instant occurredAt) {}

    private record QueuedRun(
            UUID id, MessageRoute route, Conversation conversation, Source source) {}
}
