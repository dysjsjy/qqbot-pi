package dev.qqbot.message;

import dev.qqbot.persistence.Db;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ConversationService {
    public record Conversation(UUID id, long generation) {}

    private final Db db;

    public ConversationService(Db db) {
        this.db = db;
    }

    public Conversation ensure(UUID groupId, String sender) {
        db.exec(
                "insert into conversation(id,group_id,sender_id) values(?,?,?) on conflict(group_id,sender_id) do nothing",
                UUID.randomUUID(),
                groupId,
                sender);
        var row =
                db.one(
                        "select id,generation from conversation where group_id=? and sender_id=?",
                        groupId,
                        sender);
        return new Conversation((UUID) row.get("id"), ((Number) row.get("generation")).longValue());
    }

    public void reset(UUID id) {
        db.exec(
                "update conversation set generation=generation+1,checkpoint_ref=null,updated_at=cast(unixepoch('subsec')*1000 as integer) where id=?",
                id);
        db.exec(
                "update agent_run set state='CANCELLED',finished_at=cast(unixepoch('subsec')*1000 as integer) where conversation_id=? and state in ('QUEUED','RUNNING')",
                id);
        db.exec(
                "update outbox set state='CANCELLED' where conversation_id=? and state='PENDING'",
                id);
    }

    public boolean cancel(UUID id) {
        var run =
                db.one(
                        "select id from agent_run where conversation_id=? and state='RUNNING' order by created_at desc limit 1",
                        id);
        if (run == null) {
            return false;
        }
        db.exec(
                "update agent_run set state='CANCELLED',finished_at=cast(unixepoch('subsec')*1000 as integer) where id=?",
                run.get("id"));
        db.exec(
                "update outbox set state='CANCELLED' where agent_run_id=? and state='PENDING'",
                run.get("id"));
        return true;
    }
}
