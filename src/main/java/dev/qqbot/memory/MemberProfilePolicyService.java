/**
 * [INPUT]: 依赖画像总开关、群配置与 SQLite 修订号。
 * [OUTPUT]: 读取并更新群画像策略，检查群范围和当日配额。
 * [POS]: 后台与管理端共用准入；私聊没有画像策略。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.MemberProfileProperties;
import dev.qqbot.persistence.Db;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberProfilePolicyService {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final Db db;
    private final MemberProfileProperties properties;
    private final Clock clock;

    public MemberProfilePolicyService(Db db, MemberProfileProperties properties, Clock clock) {
        this.db = db;
        this.properties = properties;
        this.clock = clock;
    }

    public record Policy(
            boolean enabled,
            boolean injectEnabled,
            int maxProfiles,
            int minMessages30d,
            int minActiveDays30d,
            int coldStartLookbackDays,
            int updateTriggerMessages,
            int updateCooldownHours,
            int dailyModelBudgetCalls,
            int profileTtlDays,
            String styleHintMode,
            Long revision) {
        private static Policy defaults() {
            return new Policy(false, false, 20, 20, 3, 90, 15, 24, 10, 60, "SUBTLE", 0L);
        }

        private void validate() {
            if (injectEnabled && !enabled
                    || !within(maxProfiles, 5, 50)
                    || !within(minMessages30d, 5, 200)
                    || !within(minActiveDays30d, 1, 30)
                    || !within(coldStartLookbackDays, 7, 365)
                    || !within(updateTriggerMessages, 5, 100)
                    || !within(updateCooldownHours, 1, 168)
                    || !within(dailyModelBudgetCalls, 1, 100)
                    || !within(profileTtlDays, 14, 180)
                    || !("SUBTLE".equals(styleHintMode) || "WARM".equals(styleHintMode))
                    || revision == null
                    || revision < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PROFILE_POLICY");
            }
        }

        private static boolean within(int value, int min, int max) {
            return value >= min && value <= max;
        }
    }

    public record View(boolean globalEnabled, Policy policy) {}

    public View get(UUID group) {
        scope(group);
        return new View(globallyEnabled(), policy(group));
    }

    public View save(UUID group, Policy input) {
        input.validate();
        return db.tx(
                () -> {
                    scope(group);
                    if (input.enabled() && !globallyEnabled()) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "PROFILE_GLOBALLY_DISABLED");
                    }
                    db.exec(
                            "insert into member_profile_policy(group_id) values(?) on conflict do nothing",
                            group);
                    if (db.exec(
                                    """
                update member_profile_policy set enabled=?,inject_enabled=?,max_profiles=?,min_messages_30d=?,min_active_days_30d=?,
                    cold_start_lookback_days=?,update_trigger_messages=?,update_cooldown_hours=?,daily_model_budget_calls=?,profile_ttl_days=?,
                    style_hint_mode=?,revision=revision+1,updated_at=? where group_id=? and revision=?
                """,
                                    input.enabled(),
                                    input.injectEnabled(),
                                    input.maxProfiles(),
                                    input.minMessages30d(),
                                    input.minActiveDays30d(),
                                    input.coldStartLookbackDays(),
                                    input.updateTriggerMessages(),
                                    input.updateCooldownHours(),
                                    input.dailyModelBudgetCalls(),
                                    input.profileTtlDays(),
                                    input.styleHintMode(),
                                    clock.instant(),
                                    group,
                                    input.revision())
                            != 1) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "PROFILE_POLICY_CONFLICT");
                    }
                    if (!input.enabled()) {
                        db.exec(
                                "update member_profile_job set state='CANCELLED',error_code='PROFILE_DISABLED',finished_at=? where group_id=? and state in ('QUEUED','RUNNING')",
                                clock.instant(),
                                group);
                    }
                    db.audit("MEMBER_PROFILE_POLICY", group);
                    return new View(globallyEnabled(), policy(group));
                });
    }

    boolean globallyEnabled() {
        return properties.enabled();
    }

    Scope scope(UUID id) {
        var row =
                db.one("select chat_type,enabled,epoch,event_seq from group_config where id=?", id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        }
        if (!"GROUP".equals(row.get("chat_type"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PROFILE_GROUP_ONLY");
        }
        return new Scope(
                id,
                count(row, "epoch"),
                count(row, "event_seq"),
                Boolean.TRUE.equals(row.get("enabled")));
    }

    record Scope(UUID group, long epoch, long eventSeq, boolean collecting) {}

    Policy policy(UUID group) {
        var row = db.one("select * from member_profile_policy where group_id=?", group);
        if (row == null) {
            return Policy.defaults();
        }
        return new Policy(
                Boolean.TRUE.equals(row.get("enabled")),
                flag(row, "inject_enabled"),
                (int) count(row, "max_profiles"),
                (int) count(row, "min_messages_30d"),
                (int) count(row, "min_active_days_30d"),
                (int) count(row, "cold_start_lookback_days"),
                (int) count(row, "update_trigger_messages"),
                (int) count(row, "update_cooldown_hours"),
                (int) count(row, "daily_model_budget_calls"),
                (int) count(row, "profile_ttl_days"),
                (String) row.get("style_hint_mode"),
                count(row, "revision"));
    }

    int remaining(UUID group, Policy policy, Instant now) {
        LocalDate day = now.atZone(ZONE).toLocalDate();
        var row =
                db.one(
                        """
            select count(*) as n from model_call where group_id=? and created_at>=? and created_at<? and purpose='MEMBER_PROFILE'
              and (dispatched or state='RUNNING')
            """,
                        group,
                        day.atStartOfDay(ZONE).toInstant(),
                        day.plusDays(1).atStartOfDay(ZONE).toInstant());
        return Math.max(0, policy.dailyModelBudgetCalls() - (int) count(row, "n"));
    }

    static long count(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    static boolean flag(Map<String, Object> row, String key) {
        return count(row, key) != 0;
    }
}
