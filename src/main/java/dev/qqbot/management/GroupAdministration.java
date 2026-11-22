/**
 * [INPUT]: 依赖 Db、Clock、部署配置、MarkdownMemory 和群成员活动查询。
 * [OUTPUT]: 提供会话 CRUD、按版本更新群管理员及停用清理编排。
 * [POS]: 配置业务层；停用取消画像作业，删群级联清除画像。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.management;

import dev.qqbot.BotProperties;
import dev.qqbot.memory.MarkdownMemory;
import dev.qqbot.persistence.Db;
import dev.qqbot.text.AdminIds;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupAdministration {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GroupAdministration.class);
    private static final String COUNTS =
            "select g.*,(select count(*) from group_message m where m.group_id=g.id and m.epoch=g.epoch) message_count,(select count(*) from memory_chunk c where c.group_id=g.id and c.epoch=g.epoch) memory_count from group_config g";
    private final Db db;
    private final BotProperties properties;
    private final MarkdownMemory files;
    private final Clock clock;

    public GroupAdministration(Db db, BotProperties properties, MarkdownMemory files) {
        this(db, properties, files, Clock.systemUTC());
    }

    @Autowired
    public GroupAdministration(Db db, BotProperties properties, MarkdownMemory files, Clock clock) {
        this.db = db;
        this.properties = properties;
        this.files = files;
        this.clock = clock;
    }

    public List<Map<String, Object>> list() {
        return db.list(COUNTS + " order by channel,name");
    }

    public Map<String, Object> get(UUID id) {
        var row = db.one(COUNTS + " where g.id=?", id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "群配置不存在，请刷新列表");
        }
        return row;
    }

    @Transactional
    public Map<String, Object> create(GroupSettings input) {
        validate(input, null);
        UUID id = UUID.randomUUID();
        db.exec(
                "insert into group_config(id,channel,account_id,external_id,name,enabled,reply_enabled,admin_ids,collection_since,connection_state) values(?,?,?,?,?,?,?,?,case when ? then cast(unixepoch('subsec')*1000 as integer) else null end,?)",
                id,
                input.channel(),
                input.accountId(),
                input.externalId(),
                input.name(),
                input.enabled(),
                input.replyEnabled(),
                input.adminIds() == null ? "" : input.adminIds(),
                input.enabled(),
                "UNKNOWN");
        db.exec(
                "insert into daily_schedule(group_id,enabled,local_time,zone,next_run_at) values(?,false,'09:00','Asia/Shanghai',(cast(unixepoch('subsec')*1000 as integer) + 86400000)) on conflict(group_id) do nothing",
                id);
        db.audit("GROUP_CREATE", id);
        log.info("GROUP_CREATE group={}", id);
        return db.one("select * from group_config where id=?", id);
    }

    @Transactional
    public Map<String, Object> update(UUID id, GroupSettings input) {
        validate(input, id);
        var old =
                db.one(
                        "select revision,external_id,channel,account_id,enabled from group_config where id=?",
                        id);
        if (old == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!Objects.equals(old.get("channel"), input.channel())
                || !Objects.equals(old.get("account_id"), input.accountId())
                || !Objects.equals(old.get("external_id"), input.externalId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "群与账号标识不可更改，请新增群配置");
        }
        if (Boolean.TRUE.equals(old.get("enabled")) && !input.enabled()) {
            db.exec(
                    "insert into collection_gap(id,group_id,starts_at,reason) values(?,?,cast(unixepoch('subsec')*1000 as integer),'GROUP_DISABLED')",
                    UUID.randomUUID(),
                    id);
        }
        if (!Boolean.TRUE.equals(old.get("enabled")) && input.enabled()) {
            db.exec(
                    "update collection_gap set ends_at=cast(unixepoch('subsec')*1000 as integer) where group_id=? and ends_at is null",
                    id);
        }
        int updated =
                db.exec(
                        "update group_config set channel=?,account_id=?,external_id=?,name=?,enabled=?,reply_enabled=?,admin_ids=?,revision=revision+1,collection_since=case when ? and collection_since is null then cast(unixepoch('subsec')*1000 as integer) else collection_since end where id=? and revision=?",
                        input.channel(),
                        input.accountId(),
                        input.externalId(),
                        input.name(),
                        input.enabled(),
                        input.replyEnabled(),
                        input.adminIds() == null ? "" : input.adminIds(),
                        input.enabled(),
                        id,
                        input.revision());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "群配置已被其他操作更新，请刷新");
        }
        if (!input.enabled()) {
            db.exec(
                    "update daily_schedule set enabled=false,revision=revision+1 where group_id=?",
                    id);
            db.exec(
                    "update activity_schedule set enabled=false,revision=revision+1 where group_id=? and enabled",
                    id);
            db.exec(
                    "update member_profile_job set state='CANCELLED',error_code='GROUP_DISABLED',finished_at=? where group_id=? and state in ('QUEUED','RUNNING')",
                    clock.instant(),
                    id);
            cancel(id, false);
        }
        if (!input.replyEnabled()) {
            cancel(id, true);
        }
        db.audit("GROUP_UPDATE", id);
        log.info(
                "GROUP_UPDATE group={} collection={} reply={}",
                id,
                input.enabled(),
                input.replyEnabled());
        return db.one("select * from group_config where id=?", id);
    }

    @Transactional
    public Map<String, Object> setMemberAdmin(
            UUID id, String senderId, boolean granted, long revision, String actor) {
        var group = GroupMembers.scope(db, id);
        var admins = AdminIds.parse((String) group.get("admin_ids"));
        if (!AdminIds.valid(senderId) && (granted || !admins.contains(senderId))) {
            throw new IllegalArgumentException("Invalid sender ID");
        }
        if (((Number) group.get("revision")).longValue() != revision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "管理员配置已变更，请刷新成员详情");
        }
        if (admins.contains(senderId) == granted) {
            return Map.of("senderId", senderId, "admin", granted, "revision", revision);
        }
        if (granted) {
            if (!GroupMembers.wasActive(db, group, senderId, clock.instant())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该群没有此成员的近期记录");
            }
            admins.add(senderId);
        } else {
            admins.remove(senderId);
        }
        String encoded = String.join(",", admins);
        if (encoded.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "管理员列表已达配置上限");
        }
        if (db.exec(
                        "update group_config set admin_ids=?,revision=revision+1 where id=? and revision=?",
                        encoded,
                        id,
                        revision)
                == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "管理员配置已变更，请刷新成员详情");
        }
        db.audit(
                granted ? "GROUP_ADMIN_GRANT" : "GROUP_ADMIN_REVOKE",
                id,
                Map.of("source", "WEB", "actor", actor, "senderId", senderId, "granted", granted));
        return Map.of("senderId", senderId, "admin", granted, "revision", revision + 1);
    }

    private void cancel(UUID id, boolean generated) {
        db.exec(
                "update agent_run set state='CANCELLED',finished_at=cast(unixepoch('subsec')*1000 as integer) where group_id=? and state in ('QUEUED','RUNNING')",
                id);
        db.exec(
                "update daily_run set state='CANCELLED',finished_at=cast(unixepoch('subsec')*1000 as integer) where group_id=? and state in "
                        + (generated ? "('QUEUED','RUNNING','GENERATED')" : "('QUEUED','RUNNING')"),
                id);
        db.exec("update outbox set state='CANCELLED' where group_id=? and state='PENDING'", id);
        db.exec(
                "update outbox set state='CANCELLED',error_code='GROUP_PAUSED' where group_id=? and result_key like 'activity:%' and state='SENDING'",
                id);
    }

    @Transactional
    public void delete(UUID id, String confirmName, long revision) {
        var row = db.one("select name,revision from group_config where id=?", id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "群配置不存在，请刷新列表");
        }
        if (!Objects.equals(row.get("name"), confirmName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "确认名称不匹配");
        }
        if (((Number) row.get("revision")).longValue() != revision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "群配置已被更新，请刷新后再删除");
        }
        files.delete(id);
        db.exec("delete from memory_document where group_id=?", id);
        for (String table :
                List.of(
                        "outbox",
                        "agent_run",
                        "daily_run",
                        "daily_schedule",
                        "memory_chunk",
                        "group_message",
                        "conversation",
                        "collection_gap")) {
            db.exec("delete from " + table + " where group_id=?", id);
        }
        db.exec("delete from group_config where id=?", id);
        db.audit("GROUP_DELETE", id);
        log.info("GROUP_DELETE group={} 已删除群配置及关联数据", id);
    }

    private void validate(GroupSettings input, UUID id) {
        if (db.one(
                        "select id from group_config where channel=? and account_id=? and external_id=? and id<>coalesce(?,'00000000-0000-0000-0000-000000000000')",
                        input.channel(),
                        input.accountId(),
                        input.externalId(),
                        id)
                != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该账号的群配置已存在，请在列表中编辑");
        }
        var gateway = properties.gateway(input.channel());
        if (input.enabled()
                && (!gateway.enabled() || !gateway.accountId().equals(input.accountId()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号必须匹配已启用的平台网关");
        }
        var existing =
                id == null
                        ? null
                        : db.one("select external_id,chat_type from group_config where id=?", id);
        if (existing != null && "PRIVATE".equals(existing.get("chat_type"))) {
            if (!Objects.equals(existing.get("external_id"), input.externalId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "私聊标识不可更改");
            }
            return;
        }
        if (input.externalId().startsWith("dm_")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dm_ 为自动发现的私聊保留标识");
        }
        if (input.channel().equals("QQ")) {
            if (!input.externalId().matches("[A-Za-z0-9_-]{1,160}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QQ 官方群 group_openid 无效");
            }
        }
        if (input.enabled()
                && ((Number)
                                        db.one(
                                                        "select count(*) n from group_config where enabled and chat_type='GROUP' and id<>coalesce(?,'00000000-0000-0000-0000-000000000000')",
                                                        id)
                                                .get("n"))
                                .intValue()
                        >= 5) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MVP最多启用5个群");
        }
    }
}
