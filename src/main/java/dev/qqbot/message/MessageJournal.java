/**
 * [INPUT]: 依赖 Db、Clock、热窗水位、群 epoch 与已认证的入站事件。
 * [OUTPUT]: 提供消息落库、去重和官方 QQ 同消息提及升级。
 * [POS]: 群活动统计与回复流程共用的采集日志；命令不进入索引。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.message;

import dev.qqbot.BotProperties;
import dev.qqbot.RetentionProperties;
import dev.qqbot.persistence.Db;
import dev.qqbot.text.Texts;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MessageJournal {
    public record Entry(UUID id, boolean duplicate) {}

    private final Db db;
    private final BotProperties properties;
    private final Clock clock;
    private final RetentionProperties retention;

    public MessageJournal(Db db, BotProperties properties) {
        this(db, properties, Clock.systemUTC());
    }

    public MessageJournal(Db db, BotProperties properties, Clock clock) {
        this(db, properties, clock, RetentionProperties.defaults());
    }

    @Autowired
    public MessageJournal(
            Db db, BotProperties properties, Clock clock, RetentionProperties retention) {
        this.db = db;
        this.properties = properties;
        this.clock = clock;
        this.retention = retention;
    }

    boolean accepts(Instant occurredAt) {
        return !occurredAt.isBefore(retention.hotCutoff(clock.instant()));
    }

    public Entry append(Group group, GroupIngestRequest request) {
        return db.tx(() -> appendLocked(group, request));
    }

    private Entry appendLocked(Group group, GroupIngestRequest request) {
        var current = db.one("select epoch,enabled from group_config where id=?", group.id());
        if (current == null
                || !Boolean.TRUE.equals(current.get("enabled"))
                || ((Number) current.get("epoch")).longValue() != group.epoch()) {
            throw new IllegalStateException("STALE_GROUP");
        }
        Instant now = clock.instant();
        Instant occurred =
                request.occurredAt().isAfter(now.plusSeconds(300)) ? now : request.occurredAt();
        var existing =
                db.one(
                        "select id,kind,sender_id,epoch,body from group_message where group_id=? and message_key=?",
                        group.id(),
                        request.messageId());
        db.exec(
                "update group_config set last_received_at=max(coalesce(last_received_at,?),?),connection_state='ONLINE' where id=?",
                occurred,
                occurred,
                group.id());
        if (existing == null) {
            UUID id = UUID.randomUUID();
            boolean nonText = "NON_TEXT".equals(request.kind());
            var counter =
                    db.one(
                            "update group_config set event_seq=event_seq+1 where id=? returning event_seq",
                            group.id());
            db.exec(
                    "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,received_at,epoch,kind,index_state,event_seq,reply_to_key) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,
                    group.id(),
                    request.messageId(),
                    request.senderId(),
                    request.senderName(),
                    nonText ? "" : request.text(),
                    occurred,
                    now,
                    group.epoch(),
                    nonText ? "NON_TEXT" : "TEXT",
                    properties.ragEnabled() ? "PENDING" : "IGNORED",
                    counter.get("event_seq"),
                    request.replyToMessageKey());
            return new Entry(id, false);
        }
        UUID id = (UUID) existing.get("id");
        boolean administrative = CommandHandler.administrative(Texts.command(request.text()));
        boolean eligible =
                administrative
                        ? Set.of("TEXT", "COMMAND").contains(existing.get("kind"))
                                && CommandHandler.canonical((String) existing.get("body"))
                                        .equals(CommandHandler.canonical(request.text()))
                                && db.one(
                                                "select 1 as found from command_receipt where group_id=? and message_key=?",
                                                group.id(),
                                                request.messageId())
                                        == null
                        : "TEXT".equals(existing.get("kind"));
        boolean upgrade =
                group.channel().equals("QQ")
                        && request.mentionsBot()
                        && group.replyEnabled()
                        && eligible
                        && existing.get("sender_id").equals(request.senderId())
                        && ((Number) existing.get("epoch")).longValue() == group.epoch()
                        && db.one("select id from agent_run where message_id=?", id) == null
                        && db.one("select id from outbox where reply_to_message_id=?", id) == null;
        return new Entry(id, !upgrade);
    }

    public void ignoreIndex(UUID id) {
        db.exec("update group_message set index_state='IGNORED' where id=?", id);
    }

    public void command(UUID id) {
        db.exec("update group_message set kind='COMMAND',index_state='IGNORED' where id=?", id);
    }
}
