package dev.qqbot.message;

import dev.qqbot.persistence.Db;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestService {
    public record Receipt(UUID groupId, UUID messageId, boolean duplicate, boolean accepted, boolean command) {}
    private final Db db;
    private final dev.qqbot.schedule.DailySummaryService summaries;

    public IngestService(Db db,dev.qqbot.schedule.DailySummaryService summaries) { this.db = db;this.summaries=summaries; }

    @Transactional
    public Receipt ingest(GroupIngestRequest request) {
        String channel = request.channel().toUpperCase();
        if (!Set.of("QQ", "WEIXIN").contains(channel)) throw new IllegalArgumentException("Unsupported channel");
        UUID groupId = ensureGroup(channel, request.accountId(), request.groupId());
        Group group = Group.from(db.one("select * from group_config where id=? for update", groupId));
        boolean nonText="NON_TEXT".equals(request.kind());
        if (!group.enabled() || request.isSelf() || (request.text().isBlank()&&!nonText)) {
            return new Receipt(groupId, null, false, false, false);
        }
        if(request.occurredAt().isBefore(Instant.now().minus(java.time.Duration.ofDays(30))))return new Receipt(groupId,null,false,false,false);

        Instant occurredAt = request.occurredAt().isAfter(Instant.now().plusSeconds(300))
                ? Instant.now() : request.occurredAt();
        UUID messageId = UUID.randomUUID();
        int inserted = db.exec("""
                insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,epoch,kind,index_state)
                values(?,?,?,?,?,?,?,?,?,'PENDING') on conflict(group_id,message_key) do nothing
                """, messageId, groupId, request.messageId(), request.senderId(), request.senderName(),
                nonText?"":request.text(), occurredAt, group.epoch(), nonText?"NON_TEXT":"TEXT");
        db.exec("update group_config set last_received_at=greatest(coalesce(last_received_at,?),?), connection_state='ONLINE' where id=?",
                occurredAt, occurredAt, groupId);
        if (inserted == 0) {
            var existing = db.one("select id from group_message where group_id=? and message_key=?", groupId, request.messageId());
            return new Receipt(groupId, (UUID) existing.get("id"), true, false, false);
        }

        if(nonText){db.exec("update group_message set index_state='IGNORED' where id=?",messageId);return new Receipt(groupId,messageId,false,true,false);}
        String command = Texts.command(request.text());
        boolean isCommand = !command.isEmpty();
        if(isCommand)db.exec("update group_message set kind='COMMAND',index_state='IGNORED' where id=?",messageId);
        if ((!isCommand && !request.mentionsBot()) || !group.replyEnabled()) return new Receipt(groupId, messageId, false, true, false);
        ensureConversation(groupId, request.senderId());
        var conversation = db.one("select id,generation from conversation where group_id=? and sender_id=?", groupId, request.senderId());
        boolean admin = group.isAdmin(request.senderId());

        if (command.equals("/summary")) {
            db.exec("update group_message set index_state='IGNORED' where id=?", messageId);
            if (!request.text().strip().equals("/summary yesterday")) {
                enqueueReply(groupId, messageId, "cmd:" + messageId, group.epoch(), "/summary yesterday 仅支持昨日摘要。");
                return new Receipt(groupId, messageId, false, true, true);
            }
            if (admin) {UUID dailyId=summaries.trigger(groupId,"COMMAND");var daily=db.one("select state from daily_run where id=?",dailyId);enqueueReply(groupId,messageId,"cmd:"+messageId,group.epoch(),"昨日分析任务："+dailyId+"（"+daily.get("state")+"）。完整报告可在管理端查看，同一天不会重复创建。");}
            else enqueueReply(groupId, messageId, "cmd:" + messageId, group.epoch(), "仅此群管理员可生成日报。");
            return new Receipt(groupId, messageId, false, admin, true);
        }

        UUID conversationId = (UUID) conversation.get("id");
        if (command.equals("/help")) {
            db.exec("update group_message set index_state='IGNORED' where id=?", messageId);
            enqueueReply(groupId, messageId, "cmd:" + messageId, group.epoch(),
                    "命令：/help /new /cancel /memory 问题。@我 可基于本群记录回答。仅本群管理员可运行 /summary yesterday。");
            return new Receipt(groupId, messageId, false, true, true);
        }
        if (command.equals("/new")) {
            db.exec("update conversation set generation=generation+1,checkpoint_ref=null,updated_at=now() where id=?", conversationId);
            db.exec("update agent_run set state='CANCELLED',finished_at=now() where conversation_id=? and state in ('QUEUED','RUNNING')", conversationId);
            db.exec("update outbox set state='CANCELLED' where conversation_id=? and state='PENDING'", conversationId);
            db.exec("update group_message set index_state='IGNORED' where id=?", messageId);
            enqueueReply(groupId, messageId, "cmd:" + messageId, group.epoch(), "当前会话已重置；群记忆仍保留。");
            return new Receipt(groupId, messageId, false, true, true);
        }
        if (command.equals("/cancel")) {
            var run = db.one("select id from agent_run where conversation_id=? and state='RUNNING' order by created_at desc limit 1", conversationId);
            if (run != null) {
                UUID runId = (UUID) run.get("id");
                db.exec("update agent_run set state='CANCELLED',finished_at=now() where id=?", runId);
                db.exec("update outbox set state='CANCELLED' where agent_run_id=? and state='PENDING'", runId);
            }
            db.exec("update group_message set index_state='IGNORED' where id=?", messageId);
            enqueueReply(groupId, messageId, "cmd:" + messageId, group.epoch(), run == null ? "当前没有运行中的任务。" : "已取消你的生成任务。");
            return new Receipt(groupId, messageId, false, true, true);
        }

        String prompt = request.text().strip();
        String kind = "CHAT";
        if (command.equals("/memory")) {
            kind = "MEMORY";
            prompt = prompt.length() > 7 ? prompt.substring(7).strip() : "";
            if (prompt.isEmpty()) {
                db.exec("update group_message set index_state='IGNORED' where id=?", messageId);
                enqueueReply(groupId, messageId, "cmd:" + messageId, group.epoch(), "用法：/memory 你的问题");
                return new Receipt(groupId, messageId, false, true, true);
            }
        } else if (isCommand) {
            db.exec("update group_message set index_state='IGNORED' where id=?", messageId);
            enqueueReply(groupId, messageId, "cmd:" + messageId, group.epoch(), "未知命令。发送 /help 查看可用命令。");
            return new Receipt(groupId, messageId, false, true, true);
        }

        UUID runId = UUID.randomUUID();
        db.one("select pg_advisory_xact_lock(11072027)");
        long queued=((Number)db.one("select count(*) as n from agent_run where state='QUEUED'").get("n")).longValue();
        long own=((Number)db.one("select count(*) as n from agent_run where conversation_id=? and state='QUEUED'",conversationId).get("n")).longValue();
        long recent=((Number)db.one("select count(*) as n from agent_run where conversation_id=? and created_at>now()-interval '1 minute'",conversationId).get("n")).longValue();
        if(queued>=20||own>=5||recent>=6){enqueueReply(groupId,messageId,"cmd:"+messageId,group.epoch(),"请求较多，请稍后再试；群消息仍已保存。");return new Receipt(groupId,messageId,false,true,isCommand);}
        db.exec("insert into agent_run(id,group_id,message_id,conversation_id,generation,epoch,kind,prompt,state) values(?,?,?,?,?,?,?,?,'QUEUED')",
                runId, groupId, messageId, conversationId, conversation.get("generation"), group.epoch(), kind, prompt);
        if(isCommand)db.exec("update group_message set kind='COMMAND',index_state='IGNORED' where id=?", messageId);
        return new Receipt(groupId, messageId, false, true, isCommand);
    }

    private UUID ensureGroup(String channel, String account, String external) {
        UUID id = UUID.randomUUID();
        db.exec("insert into group_config(id,channel,account_id,external_id,name,enabled,collection_since,connection_state) values(?,?,?,?,?,false,null,'ONLINE') on conflict(channel,account_id,external_id) do nothing",
                id, channel, account, external, external);
        return (UUID) db.one("select id from group_config where channel=? and account_id=? and external_id=?", channel, account, external).get("id");
    }

    private void ensureConversation(UUID groupId, String senderId) {
        db.exec("insert into conversation(id,group_id,sender_id) values(?,?,?) on conflict(group_id,sender_id) do nothing", UUID.randomUUID(), groupId, senderId);
    }

    private void enqueueReply(UUID groupId, UUID originId, String key, long epoch, String text) {
        int part = 0;
        for (String body : Texts.delivery(text)) {
            db.exec("insert into outbox(id,group_id,result_key,epoch,part_index,body,state) values(?,?,?,?,?,?,'PENDING') on conflict(result_key,part_index) do nothing",
                    UUID.randomUUID(), groupId, key, epoch, part++, body);
        }
    }
}
