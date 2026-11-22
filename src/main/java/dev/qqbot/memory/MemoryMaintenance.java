/**
 * [INPUT]: 依赖 Db、Clock、留存配置、归档与画像生命周期。
 * [OUTPUT]: 裁剪热索引、过期冷资产和过程流水，汇报检查点清理失败。
 * [POS]: 数据生命周期边界；群管理员配置和榜单去重标记不属于记忆正文。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.BotProperties;
import dev.qqbot.RetentionProperties;
import dev.qqbot.persistence.Db;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryMaintenance {
    private static final Duration CHECKPOINT_GRACE = Duration.ofHours(1);
    private final Db db;
    private final BotProperties properties;
    private final MarkdownArchive archive;
    private final MemorySearchIndex index;
    private final Clock clock;
    private final RetentionProperties retention;

    public MemoryMaintenance(
            Db db,
            BotProperties properties,
            MarkdownArchive archive,
            MemorySearchIndex index,
            Clock clock) {
        this(db, properties, archive, index, clock, RetentionProperties.defaults());
    }

    @Autowired
    public MemoryMaintenance(
            Db db,
            BotProperties properties,
            MarkdownArchive archive,
            MemorySearchIndex index,
            Clock clock,
            RetentionProperties retention) {
        this.db = db;
        this.properties = properties;
        this.archive = archive;
        this.index = index;
        this.clock = clock;
        this.retention = retention;
    }

    @Transactional
    public void clear(UUID groupId) {
        var group = db.one("select epoch from group_config where id=?", groupId);
        if (group == null) {
            throw new IllegalArgumentException("GROUP_NOT_FOUND");
        }
        index.clearIndex(groupId);
        archive.clear(groupId);
        MemberProfileLifecycle.clearGroup(db, groupId);
        long next = ((Number) group.get("epoch")).longValue() + 1;
        Instant now = clock.instant();
        db.exec(
                "update group_config set epoch=?,revision=revision+1,activity_epoch_since=? where id=?",
                next,
                now,
                groupId);
        db.exec(
                "update agent_run set state='CANCELLED',finished_at=cast(unixepoch('subsec')*1000 as integer),error_code='MEMORY_CLEARED' where group_id=? and state in ('QUEUED','RUNNING')",
                groupId);
        db.exec(
                "update daily_run set state='CANCELLED',finished_at=cast(unixepoch('subsec')*1000 as integer),error_code='MEMORY_CLEARED' where group_id=? and state in ('QUEUED','RUNNING','GENERATED')",
                groupId);
        db.exec(
                "update outbox set state='CANCELLED' where group_id=? and state='PENDING'",
                groupId);
        db.exec(
                "update outbox set state='CANCELLED',error_code='MEMORY_CLEARED' where group_id=? and result_key like 'activity:%' and state='SENDING'",
                groupId);
        db.exec("update outbox set body='' where group_id=?", groupId);
        db.exec(
                "update agent_run set prompt='',final_text=null,context_stats=null where group_id=?",
                groupId);
        db.exec("delete from trace_span where group_id=?", groupId);
        db.exec(
                "update daily_run set state=case when state='CANCELLED' then state else 'EXPIRED' end,report=null,source_ids=null,coverage=null,error_code='MEMORY_CLEARED' where group_id=?",
                groupId);
        db.exec(
                "delete from activity_run where group_id=? and ends_at<=?",
                groupId,
                now.minus(Duration.ofDays(1)));
        db.exec(
                """
            update activity_run set state='CLEARED',ranking=null,coverage=null,error_code='MEMORY_CLEARED',
            delivery_state=case when delivery_state='QUEUED' then 'CANCELLED' else delivery_state end where group_id=?
            """,
                groupId);
        db.exec("delete from memory_chunk where group_id=?", groupId);
        db.exec("delete from group_message where group_id=?", groupId);
        db.exec(
                "update conversation set generation=generation+1,checkpoint_ref=null where group_id=?",
                groupId);
        db.audit("MEMORY_CLEAR", groupId);
    }

    public void cleanup() {
        Instant now = clock.instant();
        Instant hotCutoff = retention.hotCutoff(now);
        Instant processCutoff = retention.processCutoff(now);
        db.tx(
                () -> {
                    db.exec(
                            "delete from memory_chunk where message_id in (select id from group_message where occurred_at<?)",
                            hotCutoff);
                    db.exec(
                            "update group_message set index_state='ARCHIVED' where occurred_at<? and index_state in ('PENDING','INDEXED','FAILED')",
                            hotCutoff);
                    db.exec(
                            "delete from memory_search_chunk where source_kind in ('RAW','SUMMARY') and source_time<?",
                            hotCutoff);
                    db.exec(
                            """
                delete from memory_search_chunk as c where source_kind='SUMMARY' and exists(
                  select 1 from memory_document_source s join group_message m on m.id=s.message_id
                  where s.document_id=c.document_id and m.occurred_at<?)
                """,
                            hotCutoff);
                    if (retention.finiteArchive()) {
                        expireAssets(retention.archiveCutoff(now), now);
                    }
                    MemberProfileLifecycle.decay(db, now);
                    db.exec(
                            "update conversation set generation=generation+1,checkpoint_ref=null where checkpoint_ref is not null and updated_at<?",
                            now.minus(Duration.ofDays(7)));
                    db.exec("delete from command_receipt where expires_at<=?", now);
                    db.exec(
                            "delete from outbox where created_at<? and state not in ('PENDING','SENDING')",
                            processCutoff);
                    db.exec(
                            "delete from agent_run where created_at<? and state not in ('QUEUED','RUNNING') and not exists(select 1 from outbox o where o.agent_run_id=agent_run.id)",
                            processCutoff);
                    db.exec(
                            "delete from admin_audit where created_at<?",
                            now.minus(Duration.ofDays(90)));
                    return null;
                });
        cleanupCheckpoints();
    }

    private void expireAssets(Instant cutoff, Instant now) {
        for (var row :
                db.list(
                        "select distinct group_id from group_message where occurred_at<?",
                        cutoff)) {
            UUID id = (UUID) row.get("group_id");
            archive.expire(id, cutoff);
            db.exec(
                    "update conversation set generation=generation+1,checkpoint_ref=null where group_id=?",
                    id);
            db.exec(
                    "update agent_run set state='CANCELLED',finished_at=cast(unixepoch('subsec')*1000 as integer),error_code='SOURCE_EXPIRED' where group_id=? and state in ('RUNNING','QUEUED')",
                    id);
            db.exec(
                    "update outbox set state='CANCELLED',body='',error_code='SOURCE_EXPIRED' where group_id=? and state='PENDING' and result_key not like 'activity:%'",
                    id);
            db.exec(
                    """
                update daily_run as r set state='EXPIRED',report=null,source_ids=null,coverage=null,error_code='SOURCE_EXPIRED'
                where r.group_id=? and r.state<>'EXPIRED' and exists(
                  select 1 from json_each(coalesce(r.source_ids,'[]')) s join group_message m on m.id=s.value
                  where m.group_id=r.group_id and m.occurred_at<?)
                """,
                    id,
                    cutoff);
            db.exec("delete from group_message where group_id=? and occurred_at<?", id, cutoff);
            MemberProfileLifecycle.unlinkExpiredSources(db, id, now);
        }
        db.exec(
                """
            update daily_run set state='EXPIRED',report=null,source_ids=null,coverage=null,error_code='SOURCE_EXPIRED'
            where state<>'EXPIRED' and created_at<?
            """,
                cutoff);
        db.exec(
                "delete from outbox where result_key in (select 'activity:'||id from activity_run where starts_at<?)",
                cutoff);
        db.exec("delete from activity_run where starts_at<?", cutoff);
    }

    public void cleanupCheckpoints() {
        var directory = properties.stateDir().toAbsolutePath().normalize().resolve("checkpoints");
        if (!Files.isDirectory(directory)) {
            return;
        }
        var retained = new HashSet<String>();
        for (var row :
                db.list(
                        "select checkpoint_ref from conversation where checkpoint_ref is not null")) {
            retained.add((String) row.get("checkpoint_ref"));
        }
        List<Path> candidates;
        try (var paths = Files.list(directory)) {
            candidates =
                    paths.filter(Files::isRegularFile)
                            .filter(path -> !retained.contains(path.toString()))
                            .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "Cannot list checkpoints: directory=" + directory, failure);
        }

        // SIDE EFFECT: 宽限期内的文件还可能等待回复发布，不能删除。
        Instant cutoff = clock.instant().minus(CHECKPOINT_GRACE);
        var failures = new ArrayList<IOException>();
        for (Path candidate : candidates) {
            try {
                if (Files.getLastModifiedTime(candidate).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(candidate);
                }
            } catch (NoSuchFileException absent) {
                continue;
            } catch (IOException failure) {
                failures.add(failure);
            }
        }
        if (!failures.isEmpty()) {
            var error =
                    new IllegalStateException(
                            "Checkpoint cleanup failed: directory="
                                    + directory
                                    + ", failures="
                                    + failures.size());
            for (IOException failure : failures) {
                error.addSuppressed(failure);
            }
            throw error;
        }
    }
}
