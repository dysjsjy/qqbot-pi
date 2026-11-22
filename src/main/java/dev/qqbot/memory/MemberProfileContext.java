/**
 * [INPUT]: 读取聊天参与者、群注入开关与当前 epoch 的紧凑画像。
 * [OUTPUT]: 返回有界的聊天画像卡和群风格档位。
 * [POS]: 聊天热路径只有一次索引查询；历史与私聊不注入。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.MemberProfileProperties;
import dev.qqbot.persistence.Db;
import dev.qqbot.text.MessageSanitizer;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MemberProfileContext {
    private static final int RECENT_HISTORY_LIMIT = 20;
    private static final String WARM_STYLE = "WARM";
    private final Db db;
    private final MemberProfileProperties properties;

    public MemberProfileContext(Db db, MemberProfileProperties properties) {
        this.db = db;
        this.properties = properties;
    }

    public record Card(String senderId, String name, String text) {}

    public record Selection(boolean enabled, boolean warm, List<Card> cards) {
        static Selection empty() {
            return new Selection(false, false, List.of());
        }
    }

    public Selection load(ContextRequest request, List<HistoryEvent> history) {
        if (!properties.enabled() || request.mode() != QueryMode.CHAT) {
            return Selection.empty();
        }
        var participants = selectParticipants(request, history);
        var rows = readCards(request.scope(), participants);
        if (rows.isEmpty()) {
            return Selection.empty();
        }

        List<Card> cards = rows.stream().flatMap(row -> safeCard(row).stream()).toList();
        return new Selection(true, WARM_STYLE.equals(rows.get(0).style()), cards);
    }

    private List<String> selectParticipants(ContextRequest request, List<HistoryEvent> history) {
        var events =
                RetrievalGate.microCompactHistory(
                        history,
                        request.conversationId(),
                        request.generation(),
                        RECENT_HISTORY_LIMIT);
        var participants = new LinkedHashSet<String>();
        participants.add(request.senderId());
        var frequency = new HashMap<String, Long>();
        // 当前提问者、引用和提及优先；只有余量留给高频发言者。
        for (HistoryEvent event : events) {
            if (event.role() != Role.USER) {
                continue;
            }
            if (event.explicitlyReferenced()) {
                participants.add(event.senderId());
            }
            frequency.merge(event.senderId(), 1L, Long::sum);
        }
        participants.addAll(MessageSanitizer.mentions(request.question()));
        var orderedSenders =
                frequency.entrySet().stream()
                        .sorted(
                                Map.Entry.<String, Long>comparingByValue()
                                        .reversed()
                                        .thenComparing(Map.Entry.comparingByKey()))
                        .map(Map.Entry::getKey)
                        .toList();
        participants.addAll(orderedSenders);
        return participants.stream().limit(properties.injectionLimit()).toList();
    }

    private List<StoredCard> readCards(MemoryScope scope, List<String> participants) {
        return db.query(
                """
                select q.style_hint_mode,p.sender_id,p.display_name,p.profile_card,p.admin_note
                from member_profile_policy q
                join group_config g on g.id=q.group_id and g.chat_type='GROUP' and g.enabled
                left join json_each(?) candidates on true
                left join member_profile p on p.group_id=g.id and p.epoch=g.epoch and p.sender_id=candidates.value
                    and p.active_slot and not p.opted_out and (p.profile_card<>'' or p.admin_note<>'')
                    and p.replay_through_seq is null and (p.locked_by_admin or not exists(
                        select 1 from member_profile_observation o
                        where o.profile_id=p.id and o.state='CONFIRMED' and o.expires_at<=?))
                where q.group_id=? and g.epoch=? and q.enabled and q.inject_enabled order by candidates.key
                """,
                (row, index) ->
                        new StoredCard(
                                row.getString(1),
                                row.getString(2),
                                row.getString(3),
                                row.getString(4),
                                row.getString(5)),
                db.json(participants),
                scope.evaluatedAt(),
                scope.groupId(),
                scope.epoch());
    }

    private static Optional<Card> safeCard(StoredCard row) {
        if (row.senderId() == null) {
            return Optional.empty();
        }
        try {
            String text = ProfileGuardrails.cleanField(row.text(), ProfileGuardrails.MAX_CARD);
            String note = ProfileGuardrails.cleanField(row.note(), ProfileGuardrails.MAX_CARD);
            if (!note.isEmpty()) {
                text =
                        ProfileGuardrails.cut(
                                "管理员备注：" + note + "；" + text, ProfileGuardrails.MAX_CARD);
            }
            return text.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new Card(row.senderId(), row.name(), text));
        } catch (IllegalArgumentException rejected) {
            // 旧卡片不满足当前文本护栏时，不将其交给聊天模型。
            return Optional.empty();
        }
    }

    private record StoredCard(
            String style, String senderId, String name, String text, String note) {}
}
