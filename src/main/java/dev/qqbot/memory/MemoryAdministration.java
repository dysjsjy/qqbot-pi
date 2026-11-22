/**
 * [INPUT]: 依赖 Db、留存口径、分段文件与记忆服务。
 * [OUTPUT]: 提供索引状态、留存概览、搜索、重试和群记忆清除。
 * [POS]: 管理端记忆业务入口；留存概览不修改数据库或文件。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.qqbot.BotProperties;
import dev.qqbot.RetentionProperties;
import dev.qqbot.persistence.Db;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemoryAdministration {
    private final Db db;
    private final MemoryService memory;
    private final BotProperties properties;
    private final MemoryMaintenance maintenance;
    private final RetentionProperties retention;
    private final RawArchiveService raw;
    private final Clock clock;

    public MemoryAdministration(
            Db db,
            MemoryService memory,
            BotProperties properties,
            MemoryMaintenance maintenance,
            RetentionProperties retention,
            RawArchiveService raw,
            Clock clock) {
        this.db = db;
        this.memory = memory;
        this.properties = properties;
        this.maintenance = maintenance;
        this.retention = retention;
        this.raw = raw;
        this.clock = clock;
    }

    public RetentionOverview retention(UUID id) {
        return db.tx(
                () -> {
                    var group = db.one("select epoch from group_config where id=?", id);
                    if (group == null) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    }
                    Object epoch = group.get("epoch");
                    var now = clock.instant();
                    var messages =
                            db.one(
                                    "select count(*) as total,min(occurred_at) as occurred_at from group_message where group_id=? and epoch=?",
                                    id,
                                    epoch);
                    var hot =
                            db.one(
                                    "select count(*) as n from group_message where group_id=? and epoch=? and occurred_at between ? and ?",
                                    id,
                                    epoch,
                                    retention.hotCutoff(now),
                                    now);
                    var segments =
                            db
                                    .list(
                                            "select * from raw_archive_segment where group_id=? and epoch=?",
                                            id,
                                            epoch)
                                    .stream()
                                    .map(RawSegment::from)
                                    .toList();
                    long rawDiskBytes = raw.diskBytes(segments);
                    long summaries =
                            count(
                                    "select count(*) from memory_document where group_id=? and epoch=? and kind='SUMMARY' and state='READY'",
                                    id,
                                    epoch);
                    long reports =
                            count(
                                    "select count(*) from daily_run where group_id=? and epoch=? and report is not null",
                                    id,
                                    epoch);
                    return new RetentionOverview(
                            retention.hotWindowDays(),
                            retention.archiveRetentionDays(),
                            retention.processRetentionDays(),
                            ((Number) messages.get("total")).longValue(),
                            ((Number) hot.get("n")).longValue(),
                            (Timestamp) messages.get("occurred_at"),
                            segments.size(),
                            rawDiskBytes,
                            summaries,
                            reports);
                });
    }

    private long count(String query, UUID id, Object epoch) {
        return db.find(query, (row, index) -> row.getLong(1), id, epoch).orElseThrow();
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RetentionOverview(
            int hotWindowDays,
            int archiveRetentionDays,
            int processRetentionDays,
            long totalMessages,
            long hotMessages,
            Timestamp earliestOccurredAt,
            int rawSegments,
            long rawDiskBytes,
            long summaryDocuments,
            long dailyReports) {}

    public Map<String, Object> status(UUID id) {
        return db.one(
                "select (select count(*) from group_message where group_id=? and epoch=(select epoch from group_config where id=?) and index_state='PENDING') pending,(select count(*) from group_message where group_id=? and epoch=(select epoch from group_config where id=?) and index_state='FAILED') failed,(select count(*) from memory_chunk where group_id=? and epoch=(select epoch from group_config where id=?)) as \"indexed\",(select max(created_at) from memory_chunk where group_id=?) last_indexed",
                id,
                id,
                id,
                id,
                id,
                id,
                id);
    }

    public List<MemorySource> search(UUID id, String question) {
        if (question.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return memory.search(id, question, 5);
    }

    public Map<String, Object> retry(UUID id) {
        if (!properties.ragEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前为无 RAG 模式，群记忆索引未启用");
        }
        int count =
                db.exec(
                        "update group_message set index_state='PENDING',index_attempts=0,error_code=null where group_id=? and index_state='FAILED' and epoch=(select epoch from group_config where id=?)",
                        id,
                        id);
        db.audit("MEMORY_RETRY", id);
        return Map.of("requeued", count);
    }

    @Transactional
    public void clear(UUID id, String confirmName) {
        var row = db.one("select name from group_config where id=?", id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!Objects.equals(row.get("name"), confirmName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "确认名称不匹配");
        }
        maintenance.clear(id);
    }
}
