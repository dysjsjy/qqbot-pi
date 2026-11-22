/**
 * [INPUT]: 依赖 Db、Clock 与 DailySummaryService。
 * [OUTPUT]: 提供群定时日报配置（时间、启用开关、场景模式、成员署名）读取、保存与手动触发。
 * [POS]: schedule 模块的配置业务层，供 ScheduleAdminController 调用。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import dev.qqbot.persistence.Db;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ScheduleSettingsService {
    private final Db db;
    private final Clock clock;
    private final DailySummaryService summaries;

    public ScheduleSettingsService(Db db, Clock clock, DailySummaryService summaries) {
        this.db = db;
        this.clock = clock;
        this.summaries = summaries;
    }

    public Map<String, Object> get(UUID id) {
        if (db.one("select id from group_config where id=?", id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "群配置不存在，请刷新群列表");
        }
        var row = db.one("select * from daily_schedule where group_id=?", id);
        if (row != null) {
            var copy = new LinkedHashMap<>(row);
            copy.putIfAbsent("style_mode", SummaryProfile.StyleMode.CASUAL.name());
            copy.putIfAbsent("speaker_mode", SummaryProfile.SpeakerMode.KEY_ONLY.name());
            if (copy.get("style_mode") == null) {
                copy.put("style_mode", SummaryProfile.StyleMode.CASUAL.name());
            }
            if (copy.get("speaker_mode") == null) {
                copy.put("speaker_mode", SummaryProfile.SpeakerMode.KEY_ONLY.name());
            }
            return Collections.unmodifiableMap(copy);
        }
        var now = ZonedDateTime.now(clock.withZone(DailySummaryService.ZONE));
        var next = now.toLocalDate().atTime(9, 0).atZone(DailySummaryService.ZONE);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Map.of(
                "group_id",
                id,
                "enabled",
                false,
                "local_time",
                "09:00:00",
                "zone",
                "Asia/Shanghai",
                "style_mode",
                "CASUAL",
                "speaker_mode",
                "KEY_ONLY",
                "revision",
                0,
                "next_run_at",
                next.toInstant());
    }

    @Transactional
    public Map<String, Object> save(UUID id, boolean enabled, LocalTime time) {
        return save(id, enabled, time, null, null);
    }

    @Transactional
    public Map<String, Object> save(
            UUID id, boolean enabled, LocalTime time, String styleMode, String speakerMode) {
        if (db.one("select id from group_config where id=? and enabled and chat_type='GROUP'", id)
                == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先启用群采集；私聊不支持群日报");
        }
        var existing =
                db.one("select style_mode,speaker_mode from daily_schedule where group_id=?", id);
        final String style, speaker;
        try {
            style =
                    (styleMode == null || styleMode.isBlank())
                                    && existing != null
                                    && existing.get("style_mode") != null
                            ? existing.get("style_mode").toString()
                            : SummaryProfile.parseStyle(styleMode).name();
            speaker =
                    (speakerMode == null || speakerMode.isBlank())
                                    && existing != null
                                    && existing.get("speaker_mode") != null
                            ? existing.get("speaker_mode").toString()
                            : SummaryProfile.parseSpeaker(speakerMode).name();
        } catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日报模式或署名配置无效");
        }
        var now = ZonedDateTime.now(clock.withZone(DailySummaryService.ZONE));
        var next = now.toLocalDate().atTime(time).atZone(DailySummaryService.ZONE);
        db.exec(
                "insert into daily_schedule(group_id,enabled,local_time,zone,style_mode,speaker_mode,next_run_at) values(?,?,?,'Asia/Shanghai',?,?,?) on conflict(group_id) do update set enabled=excluded.enabled,local_time=excluded.local_time,style_mode=excluded.style_mode,speaker_mode=excluded.speaker_mode,next_run_at=excluded.next_run_at,revision=daily_schedule.revision+1",
                id,
                enabled,
                time,
                style,
                speaker,
                next.toInstant());
        db.audit("SCHEDULE_UPDATE", id);
        return get(id);
    }

    public Map<String, Object> trigger(UUID id) {
        UUID run = summaries.trigger(id, "WEB");
        var job = db.one("select state,attempt from daily_run where id=?", run);
        return Map.of("runId", run, "state", job.get("state"), "attempt", job.get("attempt"));
    }
}
