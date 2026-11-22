/**
 * [INPUT]: 依赖待认领的摘要任务、SQLite 来源、热留存和模型生成边界。
 * [OUTPUT]: 认领和执行摘要任务，发布合格正文或记录失败与回排。
 * [POS]: 摘要执行边界。生成在事务外。发布事务复核群代际、策略与来源。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static dev.qqbot.memory.MarkdownSummaryText.*;

import dev.qqbot.MemoryProperties;
import dev.qqbot.RetentionProperties;
import dev.qqbot.memory.MarkdownSummaryText.Source;
import dev.qqbot.model.*;
import dev.qqbot.model.TextGenerator;
import dev.qqbot.persistence.Db;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class MarkdownSummaryExecution {
    private static final Logger LOG = LoggerFactory.getLogger(MarkdownSummaryExecution.class);
    static final String SOURCE_EXPIRED = "MEMORY_SOURCE_EXPIRED";
    private static final int MAX_CALLS = 120;
    private static final Duration DEADLINE = Duration.ofMinutes(30);
    private static final long SLOT_WAIT_NANOS = Duration.ofMillis(100).toNanos();
    private static final String MERGE_PREFIX = LEGACY_INSTRUCTION + "合并分块摘要：\n";
    private static final String CANCELLED = "MEMORY_CANCELLED";
    private static final String CALL_LIMIT = "MEMORY_GENERATION_LIMIT";
    static final String BACKGROUND_BUSY = "BACKGROUND_BUSY";
    private static final Set<String> ERROR_CODES =
            Set.of(
                    SOURCE_EXPIRED,
                    "MEMORY_INVALID_CITATION",
                    "MEMORY_INVALID_STRUCTURE",
                    "MEMORY_OUTPUT_LIMIT",
                    CALL_LIMIT,
                    CANCELLED,
                    "CONTEXT_TOO_LARGE",
                    MarkdownSummaryPreparation.SOURCE_TOO_LARGE);
    private final MemoryProperties budgets;
    private final Db db;
    private final MarkdownMemory files;
    private final Clock clock;
    private final RetentionProperties retention;

    MarkdownSummaryExecution(
            Db db,
            MarkdownMemory files,
            Clock clock,
            RetentionProperties retention,
            MemoryProperties budgets) {
        this.budgets = budgets;
        this.db = db;
        this.files = files;
        this.clock = clock;
        this.retention = retention;
    }

    void execute(MarkdownSummaryJob job, TextGenerator model) {
        try {
            String content = generate(job, requireSources(job), model);
            db.tx(() -> publish(job, content));
        } catch (RuntimeException failure) {
            recordFailure(job, failure);
        }
    }

    private List<Source> requireSources(MarkdownSummaryJob job) {
        List<Source> sources = readSources(job);
        if (sources.size() != job.sourceCount()) {
            throw new IllegalStateException(SOURCE_EXPIRED);
        }
        return sources;
    }

    private boolean hasAllSources(MarkdownSummaryJob job) {
        return readSources(job).size() == job.sourceCount();
    }

    private List<Source> readSources(MarkdownSummaryJob job) {
        Instant now = clock.instant();
        return db.query(
                """
                select m.id,m.occurred_at,m.sender_name,m.body from group_message m
                join memory_document_source s on s.message_id=m.id
                join group_config g on g.id=m.group_id and g.epoch=m.epoch
                where s.document_id=? and m.group_id=? and m.epoch=? and m.kind='TEXT'
                  and m.event_seq<=g.event_seq and m.occurred_at between ? and ? order by m.event_seq
                """,
                Source::read,
                job.id(),
                job.scope().groupId(),
                job.scope().epoch(),
                retention.hotCutoff(now),
                now);
    }

    private Void publish(MarkdownSummaryJob job, String content) {
        PublicationScope group = requirePublicationScope(job.scope().groupId());
        if (!isRunning(job.id()) || !verifyScope(job, group)) {
            return null;
        }
        requireSources(job);
        // SIDE EFFECT: 文件先落盘。SQLite 提交 READY 前，归档详情不会返回新正文。
        files.writeDocument(job.scope().groupId(), job.id(), "SUMMARY", content);
        db.exec(
                "update memory_document set state='READY',error_code=null,finished_at=? where id=?",
                clock.instant(),
                job.id());
        return null;
    }

    private PublicationScope requirePublicationScope(UUID group) {
        return db.find(
                        """
                        select g.epoch,g.enabled,p.mode from group_config g
                        left join memory_policy p on p.group_id=g.id where g.id=?
                        """,
                        (row, index) ->
                                new PublicationScope(
                                        row.getLong(1), row.getBoolean(2), row.getString(3)),
                        group)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "群配置不存在，请刷新列表"));
    }

    private boolean isRunning(UUID id) {
        return db.find(
                        "select 1 from memory_document where id=? and state='RUNNING'",
                        (row, index) -> true,
                        id)
                .isPresent();
    }

    private boolean verifyScope(MarkdownSummaryJob job, PublicationScope group) {
        if (group.epoch() != job.scope().epoch()) {
            db.exec(
                    """
                    update memory_document set state='CANCELLED',finished_at=cast(unixepoch('subsec')*1000 as integer)
                    where id=?
                    """,
                    job.id());
            return false;
        }
        if (!group.isEnabled()) {
            db.exec(
                    "update memory_document set state=?,error_code='GROUP_DISABLED' where id=?",
                    job.isV2() ? "CANCELLED" : "QUEUED",
                    job.id());
            return false;
        }
        if (!"SUMMARY".equals(group.mode())) {
            db.exec(
                    "update memory_document set state='CANCELLED',error_code='MEMORY_POLICY_CHANGED',finished_at=? where id=?",
                    clock.instant(),
                    job.id());
            return false;
        }
        return true;
    }

    private void recordFailure(MarkdownSummaryJob job, RuntimeException failure) {
        LOG.warn(
                "MARKDOWN_MEMORY_FAILED document={} exception={}",
                job.id(),
                failure.getClass().getSimpleName());
        String code = failure.getMessage();
        if (job.isV2() && BACKGROUND_BUSY.equals(code)) {
            db.exec(
                    """
                    update memory_document set state='QUEUED',error_code=?,finished_at=null
                    where id=? and state='RUNNING'
                    """,
                    BACKGROUND_BUSY,
                    job.id());
            return;
        }
        String error =
                failure instanceof IllegalStateException
                                && code != null
                                && ERROR_CODES.contains(code)
                        ? code
                        : "MEMORY_GENERATION_FAILED";
        db.exec(
                "update memory_document set state='FAILED',error_code=?,finished_at=? where id=? and state='RUNNING'",
                error,
                clock.instant(),
                job.id());
    }

    private record PublicationScope(long epoch, boolean isEnabled, String mode) {}

    private String generate(MarkdownSummaryJob job, List<Source> sources, TextGenerator model) {
        var session = new Run(job, model);
        String body = renderSources(sources);
        if (job.isV2()) {
            return generateV2(session, body, sources);
        }
        return legacyDocument(generateLegacy(session, body), sources);
    }

    private String generateV2(Run session, String body, List<Source> sources) {
        if (!fitsV2(body, budgets.inputTokens())) {
            throw new IllegalStateException(MarkdownSummaryPreparation.SOURCE_TOO_LARGE);
        }
        String content = request(session, V2_INSTRUCTION + body);
        var permitted = sources.stream().map(Source::id).collect(Collectors.toSet());
        SummaryDocumentFormat.validate(content, permitted);
        return content;
    }

    private String generateLegacy(Run session, String body) {
        var chunks = requestChunks(body, LEGACY_INSTRUCTION, budgets.inputTokens());
        List<String> notes = generateNotes(session, chunks, LEGACY_INSTRUCTION);
        while (notes.size() > 1) {
            var batches =
                    requestChunks(String.join("\n", notes), MERGE_PREFIX, budgets.inputTokens());
            if (batches.size() >= notes.size()) {
                throw new IllegalStateException("MEMORY_OUTPUT_LIMIT");
            }
            notes = generateNotes(session, batches, MERGE_PREFIX);
        }
        return notes.get(0);
    }

    private List<String> generateNotes(Run session, List<String> chunks, String prefix) {
        List<String> notes = new ArrayList<>();
        for (String chunk : chunks) {
            String note = request(session, prefix + chunk);
            notes.add(note);
            if (LEGACY_INSTRUCTION.equals(prefix)) {
                validateNote(note);
            }
        }
        if (MERGE_PREFIX.equals(prefix)) {
            notes.forEach(MarkdownSummaryText::validateNote);
        }
        return notes;
    }

    private String request(Run session, String prompt) {
        while (true) {
            GenerationRequest request = buildRequest(session, prompt);
            try {
                // SIDE EFFECT: 计量端口记录分发。v2 槽位不足时由作业边界回排。
                return session.model.generate(request, session).text();
            } catch (IllegalStateException failure) {
                if (session.job.isV2() || !BACKGROUND_BUSY.equals(failure.getMessage())) {
                    throw failure;
                }
                LockSupport.parkNanos(SLOT_WAIT_NANOS);
            }
        }
    }

    private GenerationRequest buildRequest(Run session, String prompt) {
        long remaining = TimeUnit.NANOSECONDS.toSeconds(session.deadline - System.nanoTime());
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException(CANCELLED);
        }
        if (remaining < 1 || session.calls >= MAX_CALLS) {
            throw new IllegalStateException(CALL_LIMIT);
        }
        var job = session.job;
        if (!isRunnable(job)) {
            throw new IllegalStateException(CANCELLED);
        }
        return new GenerationRequest(
                job.id(),
                java.util.UUID.randomUUID(),
                job.scope().groupId(),
                job.scope().epoch(),
                Purpose.MEMORY_SUMMARY,
                SYSTEM,
                prompt,
                budgets.inputTokens(),
                budgets.summaryOutputTokens(),
                (int) remaining);
    }

    private boolean isRunnable(MarkdownSummaryJob job) {
        return db.find(
                        """
                        select 1 from memory_document d join group_config g on g.id=d.group_id and g.epoch=d.epoch
                          join memory_policy p on p.group_id=d.group_id and p.mode='SUMMARY'
                        where d.id=? and d.state='RUNNING' and g.enabled
                        """,
                        (row, index) -> true,
                        job.id())
                .isPresent();
    }

    private static final class Run implements GenerationObserver {
        private final MarkdownSummaryJob job;
        private final TextGenerator model;
        private final long deadline = System.nanoTime() + DEADLINE.toNanos();
        private int calls;

        Run(MarkdownSummaryJob job, TextGenerator model) {
            this.job = job;
            this.model = model;
        }

        @Override
        public void dispatched() {
            calls++;
        }

        @Override
        public void usage(ModelUsage usage) {}
    }

    static final String DOCUMENT_FILTER =
            "select * from memory_document where id=? and group_id=? and epoch=?";
    private static final String RAW = "RAW";
    private static final String RAW_MIGRATION_REQUIRED = "RAW_MIGRATION_REQUIRED";

    Map<String, Object> retry(UUID groupId, UUID id) {
        return db.tx(() -> retryFailed(groupId, id));
    }

    private Map<String, Object> retryFailed(UUID groupId, UUID id) {
        var group = requireGroup(db, groupId);
        if (!group.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先启用群采集");
        }
        var job = requireFailedSummary(db.one(DOCUMENT_FILTER, id, groupId, group.epoch()));
        if (job.isV2() && !hasAllSources(job)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, SOURCE_EXPIRED);
        }
        return requeue(job);
    }

    private Map<String, Object> requeue(MarkdownSummaryJob job) {
        // SIDE EFFECT: 更新及审计由同一重试事务提交。
        int changed =
                db.exec(
                        """
                update memory_document set state='QUEUED',error_code=null,finished_at=null,attempt=attempt+1
                where id=? and group_id=? and epoch=? and state='FAILED' and (strategy_version=1 or attempt<3)
                """,
                        job.id(),
                        job.scope().groupId(),
                        job.scope().epoch());
        if (changed == 0) {
            throw invalidRetry();
        }
        db.audit("MARKDOWN_RETRY", job.id());
        return Map.of("id", job.id(), "state", "QUEUED");
    }

    private static MarkdownSummaryJob requireFailedSummary(Map<String, Object> row) {
        if (row != null && RAW.equals(row.get("kind"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, RAW_MIGRATION_REQUIRED);
        }
        if (row == null || !"FAILED".equals(row.get("state"))) {
            throw invalidRetry();
        }
        return MarkdownSummaryJob.from(row);
    }

    private static ResponseStatusException invalidRetry() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "仅可重试当前群的失败记忆任务");
    }

    Map<String, Object> claim() {
        return db.tx(this::claimNext);
    }

    private Map<String, Object> claimNext() {
        // SIDE EFFECT: 只有当前群和策略仍允许生成的文档才能被认领。
        return db.one(
                """
                update memory_document set state='RUNNING' where id=(
                    select d.id from memory_document d join group_config g on g.id=d.group_id
                    join memory_policy p on p.group_id=d.group_id
                    where d.state='QUEUED' and d.kind='SUMMARY' and g.enabled and g.epoch=d.epoch and p.mode='SUMMARY'
                    order by d.created_at,d.id limit 1
                ) and state='QUEUED' returning *
                """);
    }

    static GroupState requireGroup(Db db, UUID id) {
        return db.find(
                        "select epoch,enabled from group_config where id=?",
                        (row, index) -> new GroupState(row.getLong(1), row.getBoolean(2)),
                        id)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "群配置不存在，请刷新列表"));
    }

    record GroupState(long epoch, boolean isEnabled) {}

    record MarkdownSummaryJob(UUID id, Scope scope, int sourceCount, int strategyVersion) {
        static MarkdownSummaryJob from(Map<String, Object> row) {
            if (RAW.equals(row.get("kind"))) {
                throw new RawArchiveException(RAW_MIGRATION_REQUIRED);
            }
            var scope =
                    new Scope((UUID) row.get("group_id"), ((Number) row.get("epoch")).longValue());
            return new MarkdownSummaryJob(
                    (UUID) row.get("id"),
                    scope,
                    ((Number) row.get("source_count")).intValue(),
                    ((Number) row.get("strategy_version")).intValue());
        }

        boolean isV2() {
            return strategyVersion == 2;
        }

        record Scope(UUID groupId, long epoch) {}
    }
}
