/**
 * [INPUT]: 依赖 Db 当前群数据与 Clock。
 * [OUTPUT]: 提供当前 epoch 的工作区统计、定时群并集和更新时间。
 * [POS]: 管理查询的只读聚合；网关配置由 ManagementQueries 添加。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.management;

import dev.qqbot.persistence.Db;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceStatusService {
    private final Db db;
    private final Clock clock;

    public WorkspaceStatusService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public Map<String, Object> status() {
        var counts =
                db.one(
                        """
            select
              (select count(*) from group_config) registered_groups,
              (select count(*) from group_config where enabled) groups,
              (select count(*) from group_message m join group_config g
                on g.id=m.group_id and g.epoch=m.epoch) total_messages,
              (select count(*) from memory_chunk m join group_config g
                on g.id=m.group_id and g.epoch=m.epoch) indexed_memories,
              (select count(*) from group_message m join group_config g
                on g.id=m.group_id and g.epoch=m.epoch where m.index_state='PENDING') pending_index,
              (select count(*) from group_message m join group_config g
                on g.id=m.group_id and g.epoch=m.epoch where m.index_state='FAILED') failed_index,
              ((select count(*) from agent_run r join group_config g
                on g.id=r.group_id and g.epoch=r.epoch where r.state in ('QUEUED','RUNNING')) +
               (select count(*) from daily_run r join group_config g
                on g.id=r.group_id and g.epoch=r.epoch where r.state in ('QUEUED','RUNNING'))) active_runs,
              (select count(*) from outbox o join group_config g
                on g.id=o.group_id and g.epoch=o.epoch where o.state='UNKNOWN') unknown_deliveries,
              (select count(*) from group_config g where g.enabled and g.chat_type='GROUP' and
                (exists(select 1 from daily_schedule s where s.group_id=g.id and s.enabled) or
                 exists(select 1 from activity_schedule s where s.group_id=g.id and s.enabled))) scheduled_groups
            """);
        return Map.of("counts", counts, "timezone", "Asia/Shanghai", "updatedAt", clock.instant());
    }
}
