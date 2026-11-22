/**
 * [INPUT]: 依赖 Db、部署配置与当前 epoch 统计。
 * [OUTPUT]: 提供工作区状态、日报查询与结构化操作审计。
 * [POS]: 管理控制器的只读业务入口，不暴露网关凭据。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.management;

import dev.qqbot.BotProperties;
import dev.qqbot.persistence.Db;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ManagementQueries {
    private final Db db;
    private final BotProperties properties;
    private final WorkspaceStatusService workspace;

    public ManagementQueries(Db db, BotProperties properties, WorkspaceStatusService workspace) {
        this.db = db;
        this.properties = properties;
        this.workspace = workspace;
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>(workspace.status());
        var qq = properties.qq();
        status.put("ragEnabled", properties.ragEnabled());
        status.put("database", "sqlite");
        status.put(
                "gateways",
                Map.of(
                        "QQ",
                        Map.of(
                                "enabled",
                                qq.enabled(),
                                "configured",
                                !qq.official().appId().isBlank()
                                        && !qq.official().appSecret().isBlank(),
                                "accountId",
                                qq.accountId(),
                                "transport",
                                qq.official().transport(),
                                "allowActiveMessages",
                                qq.official().allowActiveMessages()),
                        "WEIXIN",
                        Map.of(
                                "enabled",
                                properties.weixin().enabled(),
                                "configured",
                                !properties.weixin().baseUrl().isBlank())));
        return status;
    }

    public List<Map<String, Object>> runs(UUID groupId, int limit, int offset) {
        int size = Math.max(1, Math.min(100, limit)), start = Math.max(0, offset);
        String query =
                "select id,group_id,business_date,state,attempt,coverage,error_code,created_at,finished_at from daily_run";
        return groupId == null
                ? db.list(query + " order by created_at desc limit ? offset ?", size, start)
                : db.list(
                        query + " where group_id=? order by created_at desc limit ? offset ?",
                        groupId,
                        size,
                        start);
    }

    public Map<String, Object> run(UUID id) {
        var row = db.one("select * from daily_run where id=?", id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return row;
    }

    public List<Map<String, Object>> audit(int limit, int offset) {
        var rows =
                db.list(
                        "select * from admin_audit order by created_at desc,id desc limit ? offset ?",
                        Math.max(1, Math.min(limit, 100)),
                        Math.max(0, offset));
        for (var row : rows) {
            if (row.get("details") instanceof String details) {
                row.put("details", db.parse(details, Map.class));
            }
        }
        return rows;
    }
}
