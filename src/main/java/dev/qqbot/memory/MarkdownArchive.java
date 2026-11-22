/**
 * [INPUT]: 依赖 Db、原始归档、Markdown 存储、摘要策略和留存水位。
 * [OUTPUT]: 提供归档查询、摘要入队与执行、重试和到期入口。
 * [POS]: 管理台与后台工作线程的归档门面。摘要组件负责选源、生成和发布。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static dev.qqbot.memory.MarkdownSummaryExecution.DOCUMENT_FILTER;
import static dev.qqbot.memory.MarkdownSummaryExecution.requireGroup;

import dev.qqbot.MemoryProperties;
import dev.qqbot.RetentionProperties;
import dev.qqbot.memory.MarkdownSummaryExecution.MarkdownSummaryJob;
import dev.qqbot.model.TextGenerator;
import dev.qqbot.persistence.Db;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MarkdownArchive {
    private static final int MAX_PAGE_SIZE = 100;
    private static final String SUMMARY = "SUMMARY";
    private static final String RAW = "RAW";
    private static final String READY = "READY";
    private final Db db;
    private final MarkdownMemory files;
    private final MemoryPolicyService policies;
    private final Clock clock;
    private final RetentionProperties retention;
    private final MarkdownSummaryPreparation preparation;
    private final MarkdownSummaryExecution execution;
    private final MarkdownArchiveRetention expiry;
    private RawArchiveService rawArchive;

    /** 接入在线原始归档，用于分段详情与状态。 */
    @Autowired
    public void setRawArchive(RawArchiveService rawArchive) {
        this.rawArchive = rawArchive;
    }

    public MarkdownArchive(
            Db db,
            MarkdownMemory files,
            MemoryPolicyService policies,
            MemoryProperties budgets,
            Clock clock) {
        this(db, files, policies, budgets, clock, RetentionProperties.defaults());
    }

    @Autowired
    public MarkdownArchive(
            Db db,
            MarkdownMemory files,
            MemoryPolicyService policies,
            MemoryProperties budgets,
            Clock clock,
            RetentionProperties retention) {
        this.db = db;
        this.files = files;
        this.policies = policies;
        this.clock = clock;
        this.retention = retention;
        this.preparation = new MarkdownSummaryPreparation(db, clock, retention, budgets);
        this.execution = new MarkdownSummaryExecution(db, files, clock, retention, budgets);
        this.expiry = new MarkdownArchiveRetention(db, files, clock);
    }

    public MarkdownArchive(Db db, MarkdownMemory files, MemoryPolicyService policies) {
        this(db, files, policies, MemoryProperties.defaults(), Clock.systemUTC());
    }

    public MarkdownArchive(Db db, MarkdownMemory files) {
        this(db, files, new MemoryPolicyService(db));
    }

    /** 读取群的归档模式、积压与文档状态。 */
    public Map<String, Object> status(UUID groupId) {
        long epoch = requireGroup(db, groupId).epoch();
        var mode =
                db.find(
                        "select mode from memory_policy where group_id=?",
                        (row, index) -> row.getString(1),
                        groupId);
        var result = new LinkedHashMap<String, Object>();
        result.put("mode", mode.orElse(RAW));
        result.put("batchSize", MarkdownSummaryPreparation.BATCH_SIZE);
        result.put("uncompressed", countUncompressed(groupId, epoch));
        result.put("counts", countDocuments(groupId, epoch));
        if (rawArchive != null) {
            result.put("rawArchive", rawArchive.status(groupId));
        }
        return result;
    }

    private Map<String, Object> countDocuments(UUID groupId, long epoch) {
        return db.one(
                """
                select count(*) documents,
                  coalesce(sum(case when state='FAILED' then 1 else 0 end),0) failed,
                  coalesce(sum(case when state in ('QUEUED','RUNNING') then 1 else 0 end),0) pending
                from memory_document where group_id=? and epoch=?
                """,
                groupId,
                epoch);
    }

    private long countUncompressed(UUID groupId, long epoch) {
        Instant now = clock.instant();
        return db.find(
                        """
                        select count(*) from group_message m
                        where m.group_id=? and m.epoch=? and m.kind='TEXT' and m.occurred_at between ? and ?
                          and not exists(select 1 from memory_document_source s where s.message_id=m.id and s.kind='SUMMARY')
                        """,
                        (row, index) -> row.getLong(1),
                        groupId,
                        epoch,
                        retention.hotCutoff(now),
                        now)
                .orElseThrow();
    }

    /** 更新旧版归档模式并返回同一事务中的状态。 */
    public Map<String, Object> configure(UUID groupId, String mode) {
        return db.tx(() -> configureAndRead(groupId, mode));
    }

    private Map<String, Object> configureAndRead(UUID groupId, String mode) {
        policies.configureLegacy(groupId, mode);
        return status(groupId);
    }

    /** 读取当前群代际的首批文档。 */
    public List<Map<String, Object>> documents(UUID groupId, int limit) {
        return documents(groupId, limit, 0);
    }

    /** 按偏移分页，偏移必须非负。 */
    public List<Map<String, Object>> documents(UUID groupId, int limit, int offset) {
        long epoch = requireGroup(db, groupId).epoch();
        if (offset < 0) {
            throw new IllegalArgumentException("Invalid archive pagination: offset=" + offset);
        }
        var rows =
                db.list(
                        """
                select d.*,exists(select 1 from raw_archive_ref r join raw_archive_segment a on a.id=r.segment_id
                  where r.document_id=d.id and a.state='ACTIVE') as open
                from memory_document d where group_id=? and epoch=? order by created_at desc,id desc limit ? offset ?
                """,
                        groupId,
                        epoch,
                        Math.min(MAX_PAGE_SIZE, Math.max(1, limit)),
                        offset);
        return rows.stream().map(MarkdownArchive::documentListItem).toList();
    }

    private static Map<String, Object> documentListItem(Map<String, Object> row) {
        row.put("open", ((Number) row.get("open")).intValue() != 0);
        return row;
    }

    /** 读取当前代际中指定归档文档的首批内容。 */
    public Map<String, Object> document(UUID groupId, UUID id) {
        return document(groupId, id, null, MAX_PAGE_SIZE);
    }

    /** 读取文档正文和来源。文档不存在、越界或到期时返回 404。 */
    public Map<String, Object> document(UUID groupId, UUID id, String cursor, int limit) {
        long epoch = requireGroup(db, groupId).epoch();
        var row = db.one(DOCUMENT_FILTER, id, groupId, epoch);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆文档不存在或已失效");
        }
        if ("SEGMENT_V1".equals(row.get("storage"))) {
            return rawArchive.readDocument(groupId, id, cursor, limit);
        }
        String kind = (String) row.get("kind");
        boolean isReady = READY.equals(row.get("state"));
        if (RAW.equals(kind) || (SUMMARY.equals(kind) && isReady)) {
            requireColdSources(
                    id,
                    new DocumentScope(groupId, epoch),
                    ((Number) row.get("source_count")).longValue());
        }
        row.put("content", isReady ? files.readDocument(groupId, id, kind) : "");
        row.put(
                "sources",
                db.list(
                        "select message_id from memory_document_source where document_id=? order by message_id",
                        id));
        return row;
    }

    private void requireColdSources(UUID id, DocumentScope scope, long expected) {
        long count =
                db.find(
                                "select count(*) from memory_document_source where document_id=?",
                                (row, index) -> row.getLong(1),
                                id)
                        .orElseThrow();
        if (count != expected || hasInvalidSource(id, scope)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private boolean hasInvalidSource(UUID id, DocumentScope scope) {
        Instant now = clock.instant();
        return db.find(
                        """
                        select 1 from memory_document_source s left join group_message m on m.id=s.message_id
                        where s.document_id=? and (m.id is null or m.group_id<>? or m.epoch<>? or m.kind<>'TEXT'
                          or m.event_seq is null or m.occurred_at<? or m.occurred_at>?) limit 1
                        """,
                        (row, index) -> true,
                        id,
                        scope.group(),
                        scope.epoch(),
                        retention.archiveCutoff(now),
                        now)
                .isPresent();
    }

    /** 恢复原始归档扫描。 */
    public RawArchiveService.Status retryRaw(UUID groupId) {
        return rawArchive.retry(groupId);
    }

    /** 重排当前群的失败摘要。v2 必须保有全部热来源，且尝试次数小于三。 */
    public Map<String, Object> retry(UUID groupId, UUID id) {
        return execution.retry(groupId, id);
    }

    /** 按当前群和摘要策略登记未归档来源，返回新建文档数。 */
    public int prepare() {
        return preparation.prepare();
    }

    /** 原子认领下一条摘要任务。没有候选时返回空值。 */
    public Map<String, Object> claim() {
        return execution.claim();
    }

    /** 执行认领的摘要任务。旧 RAW 作业须先迁移。 */
    public void process(Map<String, Object> job, TextGenerator model) {
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(model, "model must not be null");
        execution.execute(MarkdownSummaryJob.from(job), model);
    }

    /** 判断任务是否因后台槽位不足而回排。 */
    public boolean deferred(UUID id) {
        return db.find(
                        "select 1 from memory_document where id=? and state='QUEUED' and error_code=?",
                        (row, index) -> true,
                        id,
                        MarkdownSummaryExecution.BACKGROUND_BUSY)
                .isPresent();
    }

    /** 校验 v2 摘要的结构与本批来源。无效正文不会进入发布。 */
    public static void validateV2(String content, Set<UUID> permitted) {
        SummaryDocumentFormat.validate(content, permitted);
    }

    /** 清除指定群的所有归档代际。 */
    public void clear(UUID groupId) {
        expiry.clear(groupId);
    }

    /** 有限冷留存启用时裁剪到期文档。 */
    public void expire(UUID groupId) {
        if (retention.finiteArchive()) {
            expire(groupId, retention.archiveCutoff(clock.instant()));
        }
    }

    /** 按给定截止时间裁剪归档并登记分段重写。 */
    public void expire(UUID groupId, Instant cutoff) {
        if (rawArchive != null) {
            rawArchive.initialize(groupId);
        }
        expiry.expire(groupId, cutoff);
    }

    private record DocumentScope(UUID group, long epoch) {}
}
