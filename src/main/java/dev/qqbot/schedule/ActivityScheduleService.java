/**
 * [INPUT]: 依赖 Db、Clock 与日报业务时区。
 * [OUTPUT]: 提供每群活跃榜配置、版本校验和停止未发投递。
 * [POS]: 活跃榜配置不改动日报设置；快照执行由 DailyActivityService 负责。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import dev.qqbot.persistence.Db;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ActivityScheduleService {
    static final ZoneId ZONE = DailySummaryService.ZONE;
    private static final LocalTime DEFAULT_TIME = LocalTime.of(9, 0);
    private final Db db;
    private final Clock clock;

    public ActivityScheduleService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    static Map<String, Object> group(Db db, UUID id) {
        var group = db.one("select * from group_config where id=? and chat_type='GROUP'", id);
        if (group == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "群配置不存在或不是群聊");
        }
        return group;
    }

    public Map<String, Object> get(UUID id) {
        group(db, id);
        var row =
                db.one(
                        "select *,? as zone from activity_schedule where group_id=?",
                        ZONE.getId(),
                        id);
        if (row != null) {
            return row;
        }
        return Map.of(
                "group_id",
                id,
                "enabled",
                false,
                "local_time",
                "09:00:00",
                "zone",
                ZONE.getId(),
                "top_n",
                10,
                "publish_mode",
                "CONSOLE_ONLY",
                "revision",
                0L,
                "next_run_at",
                next(clock.instant(), DEFAULT_TIME));
    }

    public Map<String, Object> save(
            UUID id, boolean enabled, LocalTime time, int top, String publishMode, long revision) {
        if (top < 1 || top > 20 || !Set.of("GROUP", "CONSOLE_ONLY").contains(publishMode)) {
            throw new IllegalArgumentException("Invalid activity schedule");
        }
        return db.tx(
                () -> {
                    var group = group(db, id);
                    if (enabled && !Boolean.TRUE.equals(group.get("enabled"))) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "请先启用群采集");
                    }
                    var current = db.one("select * from activity_schedule where group_id=?", id);
                    if ((current == null ? 0L : ((Number) current.get("revision")).longValue())
                            != revision) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "活跃榜配置已变更，请刷新后重试");
                    }
                    boolean sameTime =
                            current != null
                                    && ((java.sql.Time) current.get("local_time"))
                                            .toLocalTime()
                                            .equals(time)
                                    && Objects.equals(enabled, current.get("enabled"));
                    Object nextAt =
                            sameTime ? current.get("next_run_at") : next(clock.instant(), time);
                    if (sameTime
                            && ((Number) current.get("top_n")).intValue() == top
                            && publishMode.equals(current.get("publish_mode"))) {
                        return get(id);
                    }
                    db.exec(
                            """
                insert into activity_schedule(group_id,enabled,local_time,top_n,publish_mode,next_run_at) values(?,?,?,?,?,?)
                on conflict(group_id) do update set enabled=excluded.enabled,local_time=excluded.local_time,top_n=excluded.top_n,
                  publish_mode=excluded.publish_mode,next_run_at=excluded.next_run_at,revision=activity_schedule.revision+1
                """,
                            id,
                            enabled,
                            time,
                            top,
                            publishMode,
                            nextAt);
                    if (!enabled || !"GROUP".equals(publishMode)) {
                        db.exec(
                                """
                update outbox set state='CANCELLED',error_code='ACTIVITY_PUBLICATION_DISABLED'
                where group_id=? and result_key like 'activity:%' and state in ('PENDING','SENDING')
                """,
                                id);
                    }
                    db.audit("ACTIVITY_SCHEDULE_UPDATE", id);
                    return get(id);
                });
    }

    static Instant next(Instant now, LocalTime time) {
        LocalDate today = now.atZone(ZONE).toLocalDate();
        Instant candidate = today.atTime(time).atZone(ZONE).toInstant();
        return candidate.isAfter(now)
                ? candidate
                : today.plusDays(1).atTime(time).atZone(ZONE).toInstant();
    }
}
