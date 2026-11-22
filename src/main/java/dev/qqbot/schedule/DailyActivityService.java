/**
 * [INPUT]: 依赖群活动事件、活跃榜配置、Clock、留存水位与 Db。
 * [OUTPUT]: 提供群活跃榜的昨日快照调度和历史分页。
 * [POS]: 活跃榜不用模型；榜单与可选 Outbox 投递在同一事务冻结。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import dev.qqbot.BotProperties;
import dev.qqbot.RetentionProperties;
import dev.qqbot.persistence.Db;
import java.time.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DailyActivityService {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(DailyActivityService.class);
    private static final String RANKING =
            """
        with events as (
          select id,sender_id,sender_name,occurred_at,event_seq from group_activity_event
          where group_id=? and epoch=? and occurred_at>=? and occurred_at<? and received_at<=?
        ), leaders as (
          select sender_id,count(*) as score,max(occurred_at) as last_at from events group by sender_id
          order by score desc,last_at desc,sender_id limit ?
        ), names as (
          select e.sender_id,e.sender_name,row_number() over(partition by e.sender_id order by occurred_at desc,event_seq desc,id desc) as choice
          from events e join leaders l on l.sender_id=e.sender_id where trim(e.sender_name)<>'' and e.sender_name<>e.sender_id
        )
        select l.sender_id,n.sender_name,l.score from leaders l left join names n on n.sender_id=l.sender_id and n.choice=1
        order by l.score desc,l.last_at desc,l.sender_id
        """;
    private final Db db;
    private final Clock clock;
    private final BotProperties properties;
    private final RetentionProperties retention;

    public DailyActivityService(Db db, Clock clock, BotProperties properties) {
        this(db, clock, properties, RetentionProperties.defaults());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DailyActivityService(
            Db db, Clock clock, BotProperties properties, RetentionProperties retention) {
        this.db = db;
        this.clock = clock;
        this.properties = properties;
        this.retention = retention;
    }

    @Scheduled(fixedDelay = 10_000)
    public void scheduleDue() {
        Instant now = clock.instant();
        var due =
                db.list(
                        """
            select s.group_id from activity_schedule s join group_config g on g.id=s.group_id
            where s.enabled and g.enabled and g.chat_type='GROUP' and s.next_run_at<=? order by s.next_run_at limit 20
            """,
                        now);
        for (var row : due) {
            UUID id = (UUID) row.get("group_id");
            try {
                db.tx(
                        () -> {
                            process(id, now);
                            return null;
                        });
            } catch (RuntimeException failure) {
                LOG.warn(
                        "ACTIVITY_SCHEDULE_FAILED group={} error={}",
                        id,
                        failure.getClass().getSimpleName());
            }
        }
    }

    private void process(UUID id, Instant now) {
        var settings =
                db.one(
                        "select * from activity_schedule where group_id=? and enabled and next_run_at<=?",
                        id,
                        now);
        if (settings == null) {
            return;
        }
        var group = ActivityScheduleService.group(db, id);
        if (!Boolean.TRUE.equals(group.get("enabled"))) {
            return;
        }
        var zone = ActivityScheduleService.ZONE;
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalTime time = ((java.sql.Time) settings.get("local_time")).toLocalTime();
        if (!today.atTime(time).atZone(zone).toInstant().isAfter(now)) {
            LocalDate date = today.minusDays(1);
            if (db.one("select id from activity_run where group_id=? and business_date=?", id, date)
                    == null) {
                freeze(id, date, group, settings, now);
            }
        }
        db.exec(
                "update activity_schedule set next_run_at=? where group_id=?",
                ActivityScheduleService.next(now, time),
                id);
    }

    private void freeze(
            UUID groupId,
            LocalDate date,
            Map<String, Object> group,
            Map<String, Object> settings,
            Instant snapshot) {
        var zone = ActivityScheduleService.ZONE;
        Instant start = date.atStartOfDay(zone).toInstant(),
                end = date.plusDays(1).atStartOfDay(zone).toInstant();
        Object epoch = group.get("epoch"), top = settings.get("top_n");
        Instant hotCutoff = retention.hotCutoff(snapshot);
        var rows =
                db.list(
                        RANKING,
                        groupId,
                        epoch,
                        start.isBefore(hotCutoff) ? hotCutoff : start,
                        end,
                        snapshot,
                        top);
        var ranking = new ArrayList<ActivityPresentation.Rank>();
        for (var row : rows) {
            int rank = ranking.size() + 1;
            String name =
                    ActivityPresentation.nickname(
                            (String) row.get("sender_name"), (String) row.get("sender_id"), rank);
            ranking.add(
                    new ActivityPresentation.Rank(
                            rank, name, ((Number) row.get("score")).longValue()));
        }
        boolean partial =
                start.isBefore(hotCutoff)
                        || after(group.get("collection_since"), start)
                        || after(group.get("activity_epoch_since"), start)
                        || db.one(
                                        "select 1 as found from collection_gap where group_id=? and starts_at<? and coalesce(ends_at,?)>? limit 1",
                                        groupId,
                                        end,
                                        snapshot,
                                        start)
                                != null;
        String notice =
                "仅统计已采集的群消息" + ("QQ".equals(group.get("channel")) ? "；QQ 未推送的消息（例如未 @ 事件）不计入" : "");
        var coverage = Map.of("partial", partial, "scopeNotice", notice);
        boolean requested = !ranking.isEmpty() && "GROUP".equals(settings.get("publish_mode"));
        String blocked = requested ? deliveryBlock(group) : null;
        UUID run = UUID.randomUUID();
        String state = ranking.isEmpty() ? "NO_DATA" : "GENERATED";
        String delivery = !requested ? "NOT_REQUESTED" : blocked == null ? "QUEUED" : "BLOCKED";
        db.exec(
                """
            insert into activity_run(id,group_id,business_date,epoch,snapshot_at,starts_at,ends_at,expires_at,top_n,publish_mode,state,delivery_state,error_code,coverage,ranking)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
                run,
                groupId,
                date,
                epoch,
                snapshot,
                start,
                end,
                retention.archiveExpiry(start),
                top,
                settings.get("publish_mode"),
                state,
                delivery,
                blocked,
                db.json(coverage),
                db.json(ranking));
        if (!"QUEUED".equals(delivery)) {
            return;
        }
        int partIndex = 0;
        for (String body : ActivityPresentation.render(date, ranking, partial)) {
            db.exec(
                    """
            insert into outbox(id,group_id,result_key,epoch,part_index,body,next_attempt_at) values(?,?,?,?,?,?,?)
            """,
                    UUID.randomUUID(),
                    groupId,
                    "activity:" + run,
                    epoch,
                    partIndex++,
                    body,
                    snapshot);
        }
    }

    private static boolean after(Object value, Instant start) {
        return value instanceof java.sql.Timestamp time && time.toInstant().isAfter(start);
    }

    private String deliveryBlock(Map<String, Object> group) {
        if (!Boolean.TRUE.equals(group.get("reply_enabled"))) {
            return "GROUP_REPLY_DISABLED";
        }
        String channel = (String) group.get("channel");
        var gateway = properties.gateway(channel);
        if (!gateway.enabled() || !gateway.accountId().equals(group.get("account_id"))) {
            return "GATEWAY_DISABLED";
        }
        if ("QQ".equals(channel) && !properties.qq().official().allowActiveMessages()) {
            return "QQ_ACTIVE_MESSAGES_DISABLED";
        }
        return null;
    }

    public Map<String, Object> runs(UUID id, int limit, int offset) {
        if (limit < 1 || limit > 30 || offset < 0) {
            throw new IllegalArgumentException("Invalid activity pagination");
        }
        Instant now = clock.instant();
        return db.tx(
                () -> {
                    var group = ActivityScheduleService.group(db, id);
                    String filter =
                            " from activity_run where group_id=? and epoch=? and starts_at>=? and state<>'CLEARED'";
                    Instant cutoff = retention.archiveCutoff(now);
                    var total =
                            db.one("select count(*) as n" + filter, id, group.get("epoch"), cutoff)
                                    .get("n");
                    var rows =
                            db.list(
                                    "select id,business_date,snapshot_at,starts_at,ends_at,top_n,publish_mode,state,delivery_state,error_code,coverage,ranking"
                                            + filter
                                            + " order by business_date desc limit ? offset ?",
                                    id,
                                    group.get("epoch"),
                                    cutoff,
                                    limit,
                                    offset);
                    for (var row : rows) {
                        row.put("ranking", db.parse((String) row.get("ranking"), List.class));
                    }
                    return Map.of("runs", rows, "total", total);
                });
    }
}
