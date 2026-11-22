/**
 * [INPUT]: 依赖当前群真实回复键、平台提及、活跃事件和成员清除水位。
 * [OUTPUT]: 替换成员的 30 天互动边，裁剪当前群过期互动。
 * [POS]: 由画像席位刷新在同一事务调用。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.memory.MemberProfileActivity.Window;
import dev.qqbot.memory.MemberProfilePolicyService.Scope;
import dev.qqbot.persistence.Db;
import dev.qqbot.text.MessageSanitizer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class MemberProfileEdges {
    private static final int MAX_SENDER_LENGTH = 160;
    private final Db db;

    MemberProfileEdges(Db db) {
        this.db = db;
    }

    Map<String, Long> sourceFloors(Scope scope) {
        var rows =
                db.query(
                        """
                select sender_id,source_floor_seq,opted_out from member_profile
                where group_id=? and epoch=? and (opted_out or source_floor_seq>0)
                """,
                        (row, index) -> readFloor(row),
                        scope.group(),
                        scope.epoch());
        var floors = new HashMap<String, Long>();
        for (Floor row : rows) {
            floors.put(row.sender(), row.sequence());
        }
        return Map.copyOf(floors);
    }

    private static Floor readFloor(ResultSet row) throws SQLException {
        long sequence =
                row.getBoolean("opted_out") ? Long.MAX_VALUE : row.getLong("source_floor_seq");
        return new Floor(row.getString("sender_id"), sequence);
    }

    void rebuild(Scope scope, Window window, String sender, Map<String, Long> floors) {
        var messages = readMessages(scope, window, sender, floors.getOrDefault(sender, 0L));
        var edges = aggregate(messages, floors);
        replaceEdges(scope, window, sender, edges);
    }

    private List<Message> readMessages(Scope scope, Window window, String sender, long floor) {
        return db.query(
                """
                select m.body,m.occurred_at,m.event_seq,parent.sender_id as reply_sender from group_message m
                left join group_message parent
                    on parent.group_id=m.group_id and parent.epoch=m.epoch and parent.message_key=m.reply_to_key
                where m.group_id=? and m.epoch=? and m.kind='TEXT' and m.sender_id=?
                    and m.occurred_at between ? and ? and m.received_at<=? and m.event_seq>?
                """,
                (row, index) -> readMessage(row),
                scope.group(),
                scope.epoch(),
                sender,
                window.month(),
                window.now(),
                window.now(),
                floor);
    }

    private static Message readMessage(ResultSet row) throws SQLException {
        return new Message(
                row.getString("body"),
                Instant.ofEpochMilli(row.getLong("occurred_at")),
                row.getLong("event_seq"),
                row.getString("reply_sender"));
    }

    private static Map<String, Edge> aggregate(List<Message> messages, Map<String, Long> floors) {
        var edges = new HashMap<String, Edge>();
        for (Message message : messages) {
            String reply = message.replySender();
            if (reply != null && message.sequence() > floors.getOrDefault(reply, 0L)) {
                edges.computeIfAbsent(reply, target -> new Edge()).addReply(message.occurredAt());
            }
            for (String mention : MessageSanitizer.mentions(message.body())) {
                if (message.sequence() > floors.getOrDefault(mention, 0L)) {
                    edges.computeIfAbsent(mention, target -> new Edge())
                            .addMention(message.occurredAt());
                }
            }
        }
        return edges;
    }

    private void replaceEdges(Scope scope, Window window, String sender, Map<String, Edge> edges) {
        // SIDE EFFECT: 席位刷新事务同时替换来源成员的互动边。
        db.exec(
                "delete from group_social_edge where group_id=? and epoch=? and from_sender_id=?",
                scope.group(),
                scope.epoch(),
                sender);
        for (var entry : edges.entrySet()) {
            String target = entry.getKey();
            if (!target.isBlank()
                    && !target.equals(sender)
                    && target.length() <= MAX_SENDER_LENGTH) {
                insert(scope, window, sender, new TargetEdge(target, entry.getValue()));
            }
        }
    }

    private void insert(Scope scope, Window window, String sender, TargetEdge targetEdge) {
        String target = targetEdge.sender();
        Edge edge = targetEdge.edge();
        db.exec(
                """
                insert into group_social_edge(
                    group_id,epoch,from_sender_id,to_sender_id,reply_count_30d,mention_count_30d,last_interaction_at)
                select ?,?,?,?,?,?,? where exists(
                    select 1 from group_activity_event where group_id=? and epoch=? and sender_id=?
                    and occurred_at between ? and ?)
                and not exists(
                    select 1 from member_profile where group_id=? and epoch=? and sender_id=? and opted_out)
                """,
                scope.group(),
                scope.epoch(),
                sender,
                target,
                edge.replies,
                edge.mentions,
                edge.last,
                scope.group(),
                scope.epoch(),
                target,
                window.month(),
                window.now(),
                scope.group(),
                scope.epoch(),
                target);
    }

    void expire(Scope scope, Instant cutoff) {
        db.exec(
                "delete from group_social_edge where group_id=? and epoch=? and last_interaction_at<?",
                scope.group(),
                scope.epoch(),
                cutoff);
    }

    private record Message(String body, Instant occurredAt, long sequence, String replySender) {}

    private record Floor(String sender, long sequence) {}

    private record TargetEdge(String sender, Edge edge) {}

    private static final class Edge {
        private int replies;
        private int mentions;
        private Instant last = Instant.EPOCH;

        private void addReply(Instant time) {
            replies++;
            advance(time);
        }

        private void addMention(Instant time) {
            mentions++;
            advance(time);
        }

        private void advance(Instant time) {
            if (last.isBefore(time)) {
                last = time;
            }
        }
    }
}
