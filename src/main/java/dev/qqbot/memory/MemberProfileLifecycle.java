/**
 * [INPUT]: 依赖 SQLite 画像范围、证据 UUID 与冷留存维护事务。
 * [OUTPUT]: 清除画像和互动边，建立证据下界，解绑已删除原文，衰减到期观测。
 * [POS]: 生命周期写入与群清除同事务；不触发模型或重试任务。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static dev.qqbot.memory.MemberProfilePolicyService.*;

import dev.qqbot.persistence.Db;
import java.time.Instant;
import java.util.UUID;

final class MemberProfileLifecycle {
    private MemberProfileLifecycle() {}

    static void clearGroup(Db db, UUID group) {
        db.exec("delete from member_profile_policy where group_id=?", group);
        db.exec("delete from member_profile_job where group_id=?", group);
        db.exec("delete from member_profile where group_id=?", group);
        db.exec("delete from group_social_edge where group_id=?", group);
    }

    static void clearMember(
            Db db, Scope scope, UUID profile, String sender, boolean optedOut, Instant now) {
        db.exec("delete from member_profile_observation where profile_id=?", profile);
        db.exec("delete from member_profile_job where profile_id=?", profile);
        db.exec(
                "delete from group_social_edge where group_id=? and epoch=? and (from_sender_id=? or to_sender_id=?)",
                scope.group(),
                scope.epoch(),
                sender,
                sender);
        db.exec(
                """
            update member_profile set profile_card='',admin_note='',aliases_json='[]',l0_stats_json='{}',display_name='成员',confidence_score=0,
              opted_out=?,locked_by_admin=0,active_slot=0,needs_reflection=0,pending_confirmed_changes=0,source_floor_seq=?,last_profiled_seq=?,replay_through_seq=null,
              last_profiled_at=null,last_reflected_at=null,last_active_at=null,version=version+1,updated_at=? where id=?
            """,
                optedOut,
                scope.eventSeq(),
                scope.eventSeq(),
                now,
                profile);
    }

    static void unlinkExpiredSources(Db db, UUID group, Instant now) {
        db.exec(
                """
            with retained as materialized (
              select o.id, json_group_array(s.value) filter(where m.id is not null) as ids,
                sum(m.id is null) as missing
              from member_profile_observation o join member_profile p on p.id=o.profile_id
              join json_each(o.source_message_ids_json) s
              left join group_message m on m.id=s.value and m.group_id=o.group_id and m.epoch=o.epoch and m.sender_id=p.sender_id
              where o.group_id=? group by o.id having sum(m.id is null)>0
            ) update member_profile_observation as o
            set source_message_ids_json=r.ids,source_expired_count=o.source_expired_count+r.missing,
            state=case when o.state='CANDIDATE' and json_array_length(r.ids)=0 then 'EXPIRED' else o.state end,updated_at=?
            from retained r where o.id=r.id
            """,
                group,
                now);
    }

    static void decay(Db db, Instant now) {
        db.exec(
                """
            update member_profile as p set profile_card='',needs_reflection=1,version=version+1,updated_at=?
            where not locked_by_admin and replay_through_seq is null and exists(
                select 1 from member_profile_observation o where o.profile_id=p.id and o.state='CONFIRMED' and o.expires_at<=?)
            """,
                now,
                now);
        db.exec(
                """
            update member_profile_observation as o set state='EXPIRED',updated_at=? where state in ('CANDIDATE','CONFIRMED') and expires_at<=?
                and not exists(select 1 from member_profile p where p.id=o.profile_id and p.replay_through_seq is not null)
            """,
                now,
                now);
    }
}
