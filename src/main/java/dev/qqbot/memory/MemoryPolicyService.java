/**
 * [INPUT]: 依赖 Db 的会话范围和事务。
 * [OUTPUT]: 读取归档策略，按修订号更新概括参数。
 * [POS]: 归档配置入口。不决定回复上下文。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.persistence.Db;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemoryPolicyService {
    private final Db db;

    public MemoryPolicyService(Db db) {
        this.db = db;
    }

    public Map<String, Object> settings(UUID groupId) {
        requireGroup(groupId, false);
        return values(db.one("select * from memory_policy where group_id=?", groupId));
    }

    public Map<String, Object> updateSettings(
            UUID groupId,
            long revision,
            String mode,
            int strategyVersion,
            int summaryTriggerTokens,
            int summaryCooldownMinutes) {
        if (revision < 0
                || !("RAW".equals(mode) || "SUMMARY".equals(mode))
                || (strategyVersion != 1 && strategyVersion != 2)
                || summaryTriggerTokens < 1024
                || summaryTriggerTokens > 16000
                || summaryCooldownMinutes < 5
                || summaryCooldownMinutes > 1440) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_MEMORY_SETTINGS");
        }
        return db.tx(
                () -> {
                    requireGroup(groupId, true);
                    var current =
                            db.one("select revision from memory_policy where group_id=?", groupId);
                    long actual =
                            current == null ? 0 : ((Number) current.get("revision")).longValue();
                    if (actual != revision) {
                        throw conflict();
                    }
                    if (current == null) {
                        db.exec(
                                "insert into memory_policy(group_id,revision,mode,strategy_version,summary_trigger_tokens,summary_cooldown_minutes) values(?,1,?,?,?,?)",
                                groupId,
                                mode,
                                strategyVersion,
                                summaryTriggerTokens,
                                summaryCooldownMinutes);
                    } else if (db.exec(
                                    "update memory_policy set revision=revision+1,mode=?,strategy_version=?,summary_trigger_tokens=?,summary_cooldown_minutes=? where group_id=? and revision=?",
                                    mode,
                                    strategyVersion,
                                    summaryTriggerTokens,
                                    summaryCooldownMinutes,
                                    groupId,
                                    revision)
                            != 1) {
                        throw conflict();
                    }
                    db.audit("MEMORY_SETTINGS_UPDATE", groupId);
                    return values(db.one("select * from memory_policy where group_id=?", groupId));
                });
    }

    public Map<String, Object> configureLegacy(UUID groupId, String mode) {
        if (!("RAW".equals(mode) || "SUMMARY".equals(mode))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆模式须为 RAW 或 SUMMARY");
        }
        return db.tx(
                () -> {
                    requireGroup(groupId, true);
                    db.exec(
                            "insert into memory_policy(group_id,mode,revision) values(?,?,1) on conflict(group_id) do update set mode=excluded.mode,revision=memory_policy.revision+1",
                            groupId,
                            mode);
                    db.audit("MEMORY_POLICY_UPDATE", groupId);
                    return values(db.one("select * from memory_policy where group_id=?", groupId));
                });
    }

    private void requireGroup(UUID groupId, boolean lock) {
        if (db.one("select id from group_config where id=?" + (lock ? "" : ""), groupId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "群配置不存在，请刷新列表");
        }
    }

    private static Map<String, Object> values(Map<String, Object> row) {
        if (row == null) {
            return Map.of(
                    "revision",
                    0L,
                    "mode",
                    "RAW",
                    "strategyVersion",
                    1,
                    "summaryTriggerTokens",
                    6000,
                    "summaryCooldownMinutes",
                    30);
        }
        return Map.of(
                "revision",
                ((Number) row.get("revision")).longValue(),
                "mode",
                row.get("mode"),
                "strategyVersion",
                ((Number) row.get("strategy_version")).intValue(),
                "summaryTriggerTokens",
                ((Number) row.get("summary_trigger_tokens")).intValue(),
                "summaryCooldownMinutes",
                ((Number) row.get("summary_cooldown_minutes")).intValue());
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "MEMORY_SETTINGS_CONFLICT");
    }
}
