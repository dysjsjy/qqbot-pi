/**
 * [INPUT]: 依赖 SQLite 回复任务、群配置、会话代际与出站队列。
 * [OUTPUT]: 认领运行，记录状态并原子发布已校验回复。
 * [POS]: 回复执行器的 SQLite 边界。模型生成在发布事务外完成。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.message;

import dev.qqbot.persistence.Db;
import dev.qqbot.trace.Trace;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AgentRunStore {
    private static final Logger LOG = LoggerFactory.getLogger(AgentWorker.class);
    private static final String FINISHED_AT = "cast(unixepoch('subsec')*1000 as integer)";
    private static final String OUTBOX_INSERT =
            """
            insert into outbox(id,group_id,result_key,agent_run_id,conversation_id,generation,epoch,
              part_index,body,reply_to_message_id) values(?,?,?,?,?,?,?,?,?,?)
            on conflict(result_key,part_index) do nothing
            """;
    static final String STALE_RESULT = "STALE_RESULT";
    private final Db db;

    AgentRunStore(Db db) {
        this.db = db;
    }

    Optional<Job> claim() {
        return db.find(
                """
                update agent_run set state='RUNNING' where id=(
                  select r.id from agent_run r join group_config g on g.id=r.group_id
                  join conversation v on v.id=r.conversation_id
                  where r.state='QUEUED' and g.enabled and g.reply_enabled and r.epoch=g.epoch and r.generation=v.generation
                    and not exists(select 1 from agent_run a where a.conversation_id=r.conversation_id and a.state='RUNNING')
                  order by r.created_at,r.id limit 1) and state='QUEUED' returning *
                """,
                Job::read);
    }

    List<Job> queuedBefore(Instant cutoff) {
        return db.query(
                "select * from agent_run where state='QUEUED' and created_at<?", Job::read, cutoff);
    }

    boolean isRunning(UUID id) {
        return db.find(
                        "select 1 from agent_run where id=? and state='RUNNING'",
                        (row, index) -> true,
                        id)
                .isPresent();
    }

    boolean isCurrent(Job job) {
        return db.find(
                        """
                        select 1 from agent_run r join group_config g on g.id=r.group_id
                        join conversation c on c.id=r.conversation_id
                        where r.id=? and r.state='RUNNING' and g.enabled and g.reply_enabled
                          and g.epoch=r.epoch and c.generation=r.generation
                          and r.epoch=? and r.generation=?
                        """,
                        (row, index) -> true,
                        job.id(),
                        job.route().epoch(),
                        job.route().generation())
                .isPresent();
    }

    void requireCurrent(Job job) {
        if (!isCurrent(job)) {
            throw new CancellationException();
        }
    }

    String requireOwner(Job job) {
        var route = job.route();
        return db.find(
                        "select sender_id from conversation where id=? and group_id=?",
                        (row, index) -> row.getString(1),
                        route.conversationId(),
                        route.groupId())
                .orElseThrow(CancellationException::new);
    }

    void cancel(Job job) {
        db.exec(
                "update agent_run set state='CANCELLED',error_code=?,finished_at="
                        + FINISHED_AT
                        + " where id=? and state='RUNNING'",
                STALE_RESULT,
                job.id());
    }

    void expire(Job job) {
        finishFailed(job, "EXPIRED", "QUEUE_TIMEOUT", "请求排队超时，请稍后重试。");
    }

    void fail(Job job, String code) {
        finishFailed(job, "FAILED", code, "本次生成失败，请稍后重试；群消息已保存。");
    }

    private void finishFailed(Job job, String state, String code, String message) {
        db.tx(() -> publishFailure(job, state, code, message));
    }

    private Void publishFailure(Job job, String state, String code, String message) {
        boolean isGroupActive =
                db.find(
                                """
                        select 1 from group_config where id=? and epoch=? and enabled and reply_enabled
                        """,
                                (row, index) -> true,
                                job.route().groupId(),
                                job.route().epoch())
                        .isPresent();
        int changed =
                db.exec(
                        "update agent_run set state=?,error_code=?,finished_at="
                                + FINISHED_AT
                                + " where id=? and state in ('QUEUED','RUNNING')",
                        state,
                        code,
                        job.id());
        if (changed > 0 && isGroupActive) {
            enqueue(job, "failure:" + job.id(), 0, message);
        }
        return null;
    }

    void complete(Job job, Answer answer) {
        try (var span = Trace.span("agent.complete")) {
            db.tx(() -> publishAnswer(job, answer));
        }
    }

    private Void publishAnswer(Job job, Answer answer) {
        if (!isRunning(job.id())) {
            return null;
        }
        if (!isCurrent(job)) {
            cancel(job);
            return null;
        }
        // SIDE EFFECT: 状态、检查点和出站分段随发布事务一起提交。
        db.exec(
                "update agent_run set state='SUCCEEDED',final_text=?,finished_at="
                        + FINISHED_AT
                        + " where id=?",
                answer.display(),
                job.id());
        LOG.info("AGENT_SUCCEEDED run={} group={}", job.id(), job.route().groupId());
        db.exec(
                "update conversation set checkpoint_ref=?,updated_at="
                        + FINISHED_AT
                        + " where id=?",
                answer.checkpoint(),
                job.route().conversationId());
        int index = 0;
        String key = job.id().toString();
        for (String part : answer.parts()) {
            enqueue(job, key, index, part);
            index++;
        }
        return null;
    }

    private void enqueue(Job job, String key, int index, String body) {
        var route = job.route();
        db.exec(
                OUTBOX_INSERT,
                UUID.randomUUID(),
                route.groupId(),
                key,
                job.id(),
                route.conversationId(),
                route.generation(),
                route.epoch(),
                index,
                body,
                job.input().trigger().messageId());
    }

    record Answer(String display, String checkpoint, List<String> parts) {
        Answer {
            parts = List.copyOf(parts);
        }
    }

    public record Job(UUID id, Route route, Input input, Instant queuedAt) {
        static Job read(ResultSet row, int index) throws SQLException {
            return new Job(id(row, "id"), readRoute(row), readInput(row), time(row, "created_at"));
        }

        private static Route readRoute(ResultSet row) throws SQLException {
            return new Route(
                    id(row, "group_id"),
                    id(row, "conversation_id"),
                    row.getLong("epoch"),
                    row.getLong("generation"));
        }

        private static Input readInput(ResultSet row) throws SQLException {
            UUID message = id(row, "message_id");
            Long sequence = number(row, "context_seq");
            Instant occurredAt = time(row, "context_occurred_at");
            return new Input(
                    row.getString("kind"),
                    row.getString("prompt"),
                    new Trigger(message, sequence, occurredAt));
        }

        private static UUID id(ResultSet row, String column) throws SQLException {
            String value = row.getString(column);
            return value == null ? null : UUID.fromString(value);
        }

        private static Instant time(ResultSet row, String column) throws SQLException {
            Long value = number(row, column);
            return value == null ? null : Instant.ofEpochMilli(value);
        }

        private static Long number(ResultSet row, String column) throws SQLException {
            long value = row.getLong(column);
            return row.wasNull() ? null : value;
        }

        public record Route(UUID groupId, UUID conversationId, long epoch, long generation) {}

        public record Input(String kind, String prompt, Trigger trigger) {}

        public record Trigger(UUID messageId, Long beforeSeq, Instant occurredAt) {}
    }
}
