/**
 * [INPUT]: 依赖 Db、会话范围和热窗水位。
 * [OUTPUT]: 读取当前代次可见的消息和已送达回复。
 * [POS]: 为聊天组装提供有序热历史，不读取冷归档。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.RetentionProperties;
import dev.qqbot.persistence.Db;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ConversationHistory {
    private static final String USERS =
            """
        select m.id,m.event_seq,m.occurred_at,m.sender_id,m.sender_name,m.body,
          r.conversation_id,r.generation,m.reply_to_key,'USER' as role,null as message_id
        from group_message m left join agent_run r on r.message_id=m.id and r.group_id=m.group_id and r.epoch=m.epoch
        where m.group_id=? and m.epoch=? and m.kind='TEXT' and m.event_seq<?
          and m.occurred_at<=? and m.occurred_at>=?
        """;
    private static final String ASSISTANTS =
            """
        select o.id,o.event_seq,o.delivered_at as occurred_at,'BOT' as sender_id,'机器人' as sender_name,o.body,
          r.conversation_id,r.generation,null as reply_to_key,'ASSISTANT' as role,r.message_id
        from outbox o join agent_run r on r.id=o.agent_run_id and r.group_id=o.group_id and r.epoch=o.epoch
        where o.group_id=? and o.epoch=? and o.state='SENT' and r.state='SUCCEEDED'
          and o.result_key=cast(r.id as text) and o.event_seq<?
          and o.delivered_at<=? and o.delivered_at>=?
        """;
    private final Db db;
    private final RetentionProperties retention;

    public ConversationHistory(Db db) {
        this(db, RetentionProperties.defaults());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ConversationHistory(Db db, RetentionProperties retention) {
        this.db = db;
        this.retention = retention;
    }

    public List<HistoryEvent> load(ContextRequest request) {
        var scope = request.scope();
        var group = db.one("select enabled,epoch from group_config where id=?", scope.groupId());
        if (group == null
                || !Boolean.TRUE.equals(group.get("enabled"))
                || ((Number) group.get("epoch")).longValue() != scope.epoch()) {
            return List.of();
        }
        Object[] boundary = boundary(scope);
        var recent =
                db.list(
                        "select * from ("
                                + USERS
                                + " union all "
                                + ASSISTANTS
                                + ") events order by event_seq desc limit 80",
                        concat(boundary, boundary));
        var selected = new LinkedHashMap<String, HistoryEvent>();
        if (scope.triggerMessageId() != null) {
            var trigger =
                    db.one(
                            "select reply_to_key from group_message where id=? and group_id=? and epoch=?",
                            scope.triggerMessageId(),
                            scope.groupId(),
                            scope.epoch());
            String reference = trigger == null ? null : (String) trigger.get("reply_to_key");
            if (reference != null) {
                var quoted =
                        db.one(
                                USERS + " and m.message_key=?",
                                concat(boundary, new Object[] {reference}));
                if (quoted == null) {
                    quoted =
                            db.one(
                                    ASSISTANTS
                                            + " and o.platform_id=? order by o.event_seq desc limit 1",
                                    concat(boundary, new Object[] {reference}));
                }
                if (quoted != null) {
                    add(selected, event(quoted, true));
                }
            }
        }
        var own =
                db.list(
                        """
            select r.id as run_id,max(o.event_seq) as last_seq from outbox o
            join agent_run r on r.id=o.agent_run_id and r.group_id=o.group_id and r.epoch=o.epoch
            where o.group_id=? and o.epoch=? and o.state='SENT' and r.state='SUCCEEDED'
              and o.result_key=cast(r.id as text) and o.event_seq<? and o.delivered_at<=? and o.delivered_at>=?
              and r.conversation_id=? and r.generation=?
            group by r.id order by max(o.event_seq) desc limit 4
            """,
                        scope.groupId(),
                        scope.epoch(),
                        scope.beforeSeq(),
                        scope.occurredBeforeOrAt(),
                        scope.evaluatedAt().minus(Duration.ofHours(24)),
                        request.conversationId(),
                        request.generation());
        for (var run : own) {
            UUID runId = (UUID) run.get("run_id");
            var question = db.one(USERS + " and r.id=?", concat(boundary, new Object[] {runId}));
            if (question != null) {
                add(selected, event(question, false));
            }
            for (var answer :
                    db.list(
                            ASSISTANTS + " and r.id=? order by o.event_seq",
                            concat(boundary, new Object[] {runId}))) {
                add(selected, event(answer, false));
            }
        }
        for (var row : recent) {
            add(selected, event(row, false));
        }
        return selected.values().stream()
                .sorted(
                        Comparator.comparingLong(HistoryEvent::seq)
                                .thenComparing(HistoryEvent::sourceKey))
                .toList();
    }

    private void add(Map<String, HistoryEvent> selected, HistoryEvent event) {
        if (selected.size() < 100 || selected.containsKey(event.sourceKey())) {
            selected.putIfAbsent(event.sourceKey(), event);
        }
    }

    private Object[] boundary(MemoryScope scope) {
        return new Object[] {
            scope.groupId(),
            scope.epoch(),
            scope.beforeSeq(),
            scope.occurredBeforeOrAt(),
            retention.hotCutoff(scope.evaluatedAt())
        };
    }

    private Object[] concat(Object[] first, Object[] second) {
        Object[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private HistoryEvent event(Map<String, Object> row, boolean referenced) {
        Role role = Role.valueOf((String) row.get("role"));
        String key =
                (role == Role.USER ? "message:" : "outbox:")
                        + row.get("id")
                        + (role == Role.USER ? ":0" : "");
        String reply =
                role == Role.ASSISTANT && row.get("message_id") != null
                        ? "message:" + row.get("message_id") + ":0"
                        : (String) row.get("reply_to_key");
        return new HistoryEvent(
                key,
                ((Number) row.get("event_seq")).longValue(),
                ((Timestamp) row.get("occurred_at")).toInstant(),
                role,
                (String) row.get("sender_id"),
                (String) row.get("sender_name"),
                (String) row.get("body"),
                (UUID) row.get("conversation_id"),
                row.get("generation") == null ? null : ((Number) row.get("generation")).longValue(),
                reply,
                referenced);
    }
}
