/**
 * [INPUT]: 依赖 Db 与按群配置的回复参数。
 * [OUTPUT]: 提供网页和群内命令共用的策略更新、版本校验与回复决策。
 * [POS]: 策略配置与动态能量共用一条记录，能量变化不推进配置版本。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.message;

import dev.qqbot.persistence.Db;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReplyPolicyService {
    private static final Set<String> MODES = Set.of("MENTION", "PROBABILITY", "ENERGY");

    public record Patch(String mode, Double probability, Integer gain, Integer cost) {
        public Patch {
            if (mode == null
                    || !MODES.contains(mode)
                    || (probability != null
                            && (!Double.isFinite(probability)
                                    || probability < 0
                                    || probability > 1))
                    || (gain != null && (gain < 1 || gain > 1000))
                    || (cost != null && (cost < 1 || cost > 1000))) {
                throw new IllegalArgumentException("Invalid reply policy");
            }
            if (probability != null && probability == 0) {
                probability = 0.0;
            }
        }
    }

    private final Db db;

    public ReplyPolicyService(Db db) {
        this.db = db;
    }

    public Map<String, Object> get(UUID id) {
        if (db.one("select id from group_config where id=? and chat_type='GROUP'", id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "群配置不存在或不是群聊");
        }
        var row = db.one("select * from reply_policy where group_id=?", id);
        return row == null
                ? Map.of(
                        "mode",
                        "MENTION",
                        "probability",
                        0.1,
                        "energy",
                        0,
                        "energy_gain",
                        1,
                        "energy_cost",
                        10,
                        "revision",
                        0L)
                : row;
    }

    public Map<String, Object> save(
            UUID id, Patch patch, Long expectedRevision, String source, String actor) {
        return db.tx(
                () -> {
                    var current = get(id);
                    long revision = ((Number) current.get("revision")).longValue();
                    if (expectedRevision != null && expectedRevision != revision) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "回复设置已变更，请刷新后重试");
                    }
                    var before = settings(current);
                    var after =
                            new Patch(
                                    patch.mode(),
                                    patch.probability() == null
                                            ? before.probability()
                                            : patch.probability(),
                                    patch.gain() == null ? before.gain() : patch.gain(),
                                    patch.cost() == null ? before.cost() : patch.cost());
                    if (before.equals(after)) {
                        return current;
                    }
                    int updated =
                            db.exec(
                                    """
                insert into reply_policy(group_id,mode,probability,energy_gain,energy_cost,revision) values(?,?,?,?,?,?)
                on conflict(group_id) do update set mode=excluded.mode,probability=excluded.probability,energy_gain=excluded.energy_gain,
                energy_cost=excluded.energy_cost,energy=0,revision=excluded.revision where reply_policy.revision=?
                """,
                                    id,
                                    after.mode(),
                                    after.probability(),
                                    after.gain(),
                                    after.cost(),
                                    revision + 1,
                                    revision);
                    if (updated == 0) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "回复设置已变更，请刷新后重试");
                    }
                    db.audit(
                            "REPLY_POLICY_UPDATE",
                            id,
                            Map.of(
                                    "source", source, "actor", actor, "before", before, "after",
                                    after));
                    return get(id);
                });
    }

    private static Patch settings(Map<String, Object> row) {
        return new Patch(
                (String) row.get("mode"),
                ((Number) row.get("probability")).doubleValue(),
                ((Number) row.get("energy_gain")).intValue(),
                ((Number) row.get("energy_cost")).intValue());
    }

    public boolean shouldReply(UUID groupId) {
        var policy = db.one("select * from reply_policy where group_id=?", groupId);
        if (policy == null || "MENTION".equals(policy.get("mode"))) {
            return false;
        }
        if ("PROBABILITY".equals(policy.get("mode"))) {
            return ThreadLocalRandom.current().nextDouble()
                    < ((Number) policy.get("probability")).doubleValue();
        }
        int gain = ((Number) policy.get("energy_gain")).intValue(),
                cost = ((Number) policy.get("energy_cost")).intValue();
        int energy = Math.min(cost, ((Number) policy.get("energy")).intValue() + gain);
        db.exec("update reply_policy set energy=? where group_id=?", energy, groupId);
        return energy >= cost;
    }

    public void consume(UUID groupId) {
        db.exec(
                "update reply_policy set energy=energy-energy_cost where group_id=? and mode='ENERGY' and energy>=energy_cost",
                groupId);
    }
}
