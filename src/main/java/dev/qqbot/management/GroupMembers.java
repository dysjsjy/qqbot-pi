/**
 * [INPUT]: 依赖 Db、Clock 和群管理员标识规则。
 * [OUTPUT]: 提供 7、30、90 天群成员分页、详情及只读画像状态。
 * [POS]: 聚合当前群 epoch；管理员授予仍按 30 天活动验证。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.management;

import dev.qqbot.persistence.Db;
import dev.qqbot.text.AdminIds;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupMembers {
    private static final int ADMIN_ACTIVITY_DAYS = 30;
    private static final int MAX_WINDOW_DAYS = 90;
    private static final String STATS =
            """
        with scope(group_id,epoch,since,as_of) as (values(?,?,?,?)),
        counts as (
          select sender_id,count(*) as message_count,max(occurred_at) as last_at from scope s join group_activity_event e
          on e.group_id=s.group_id and e.epoch=s.epoch and e.occurred_at between s.since and s.as_of and e.received_at<=s.as_of
          group by sender_id
        ), admins as (select value as sender_id from json_each(?)),
        candidates as (
          select sender_id from counts where not ? union select sender_id from admins where ?
        ), members as materialized (
          select m.sender_id,coalesce((select e.sender_name from scope s join group_activity_event e
            on e.group_id=s.group_id and e.epoch=s.epoch and e.sender_id=m.sender_id
            where e.occurred_at between s.since and s.as_of and e.received_at<=s.as_of and trim(e.sender_name)<>'' and e.sender_name<>e.sender_id
            order by e.occurred_at desc,e.event_seq desc,e.id desc limit 1),m.sender_id) as sender_name,
                 coalesce(c.message_count,0) as message_count,
                 c.last_at,exists(select 1 from admins a where a.sender_id=m.sender_id) as admin
          from candidates m left join counts c on c.sender_id=m.sender_id
        ), filtered as (
          select * from members where instr(lower(sender_id),lower(?))>0 or instr(lower(sender_name),lower(?))>0
        )
        """;
    private final Db db;
    private final Clock clock;

    public GroupMembers(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    static Map<String, Object> scope(Db db, UUID id) {
        var group =
                db.one(
                        "select id,name,channel,account_id,epoch,revision,admin_ids from group_config where id=? and chat_type='GROUP'",
                        id);
        if (group == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "群配置不存在或不是群聊");
        }
        return group;
    }

    static boolean wasActive(Db db, Map<String, Object> group, String senderId, Instant now) {
        return db.one(
                        "select 1 as found from group_activity_event where group_id=? and epoch=? and sender_id=? and occurred_at between ? and ? and received_at<=? limit 1",
                        group.get("id"),
                        group.get("epoch"),
                        senderId,
                        now.minus(Duration.ofDays(ADMIN_ACTIVITY_DAYS)),
                        now,
                        now)
                != null;
    }

    public Map<String, Object> list(
            UUID id,
            int days,
            String query,
            String sort,
            boolean adminOnly,
            int limit,
            int offset) {
        if ((days != 7 && days != 30 && days != MAX_WINDOW_DAYS)
                || query.length() > 200
                || limit < 1
                || limit > 100
                || offset < 0) {
            throw new IllegalArgumentException("Invalid member query");
        }
        String order =
                switch (sort) {
                    case "messages" -> "message_count desc,last_at desc,sender_id";
                    case "recent" -> "last_at desc,message_count desc,sender_id";
                    default -> throw new IllegalArgumentException("Invalid member sort");
                };
        Instant now = clock.instant();
        return db.tx(
                () -> {
                    var group = scope(db, id);
                    var rows =
                            db.list(
                                    STATS
                                            + "select page.*,total.n from (select count(*) as n from filtered) total left join "
                                            + "(select * from filtered order by "
                                            + order
                                            + " limit ? offset ?) page on true order by "
                                            + order,
                                    id,
                                    group.get("epoch"),
                                    now.minus(Duration.ofDays(days)),
                                    now,
                                    db.json(AdminIds.parse((String) group.get("admin_ids"))),
                                    adminOnly,
                                    adminOnly,
                                    query.strip(),
                                    query.strip(),
                                    limit,
                                    offset);
                    var members =
                            rows.stream()
                                    .filter(row -> row.get("sender_id") != null)
                                    .map(GroupMembers::view)
                                    .toList();
                    return Map.of(
                            "members",
                            members,
                            "total",
                            rows.get(0).get("n"),
                            "asOf",
                            now,
                            "revision",
                            group.get("revision"));
                });
    }

    public Map<String, Object> detail(UUID id, String senderId) {
        if (senderId.isBlank() || senderId.length() > 2000) {
            throw new IllegalArgumentException("Invalid sender ID");
        }
        Instant now = clock.instant();
        return db.tx(
                () -> {
                    var group = scope(db, id);
                    Instant week = now.minus(Duration.ofDays(7)),
                            month = now.minus(Duration.ofDays(30)),
                            oldest = now.minus(Duration.ofDays(MAX_WINDOW_DAYS));
                    var stats =
                            db.one(
                                    """
                    select count(*) as message_count,coalesce(sum(occurred_at>=?),0) as messages_7d,
                        coalesce(sum(occurred_at>=?),0) as messages_30d,max(occurred_at) as last_at
                    from group_activity_event where group_id=? and epoch=? and sender_id=? and occurred_at between ? and ? and received_at<=?
                    """,
                                    week,
                                    month,
                                    id,
                                    group.get("epoch"),
                                    senderId,
                                    oldest,
                                    now,
                                    now);
                    boolean admin =
                            AdminIds.parse((String) group.get("admin_ids")).contains(senderId);
                    if (!admin && ((Number) stats.get("message_count")).longValue() == 0) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该群没有此成员的近期记录");
                    }
                    var name =
                            db.one(
                                    """
                    select sender_name from group_activity_event where group_id=? and epoch=? and sender_id=? and occurred_at between ? and ? and received_at<=?
                    and trim(sender_name)<>'' and sender_name<>sender_id order by occurred_at desc,event_seq desc,id desc limit 1
                    """,
                                    id,
                                    group.get("epoch"),
                                    senderId,
                                    oldest,
                                    now,
                                    now);
                    var profile =
                            db.one(
                                    "select opted_out,profile_card<>'' as has_card from member_profile where group_id=? and epoch=? and sender_id=?",
                                    id,
                                    group.get("epoch"),
                                    senderId);
                    boolean optedOut =
                            profile != null && ((Number) profile.get("opted_out")).intValue() != 0;
                    boolean hasProfile =
                            !optedOut
                                    && profile != null
                                    && ((Number) profile.get("has_card")).intValue() != 0;
                    return new Member(
                                    senderId,
                                    name == null ? senderId : (String) name.get("sender_name"),
                                    ((Number) stats.get("messages_30d")).longValue(),
                                    timestamp(stats.get("last_at")),
                                    admin)
                            .details(
                                    group,
                                    stats.get("messages_7d"),
                                    stats.get("message_count"),
                                    now,
                                    optedOut,
                                    hasProfile);
                });
    }

    private static Instant timestamp(Object value) {
        return value == null ? null : Instant.ofEpochMilli(((Number) value).longValue());
    }

    private static Member view(Map<String, Object> row) {
        return new Member(
                (String) row.get("sender_id"),
                (String) row.get("sender_name"),
                ((Number) row.get("message_count")).longValue(),
                timestamp(row.get("last_at")),
                ((Number) row.get("admin")).intValue() != 0);
    }

    public record Member(
            String senderId, String senderName, long messageCount, Instant lastAt, boolean admin) {
        Map<String, Object> details(
                Map<String, Object> group,
                Object messages7d,
                Object messages90d,
                Instant now,
                boolean optedOut,
                boolean hasProfile) {
            return Map.ofEntries(
                    Map.entry("member", this),
                    Map.entry("messages7d", messages7d),
                    Map.entry("messages30d", messageCount),
                    Map.entry("messages90d", messages90d),
                    Map.entry("asOf", now),
                    Map.entry("groupName", group.get("name")),
                    Map.entry("accountId", group.get("account_id")),
                    Map.entry("channel", group.get("channel")),
                    Map.entry("revision", group.get("revision")),
                    Map.entry("optedOut", optedOut),
                    Map.entry("hasProfile", hasProfile));
        }
    }
}
