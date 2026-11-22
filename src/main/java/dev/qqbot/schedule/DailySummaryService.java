/**
 * [INPUT]: 依赖 SQLite 日报任务、快照认领和业务时钟。
 * [OUTPUT]: 创建、认领、重试、查询和发布日报任务。
 * [POS]: 日报队列与发布事务的入口。认领模块冻结模型输入。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import dev.qqbot.BotProperties;
import dev.qqbot.persistence.Db;
import dev.qqbot.schedule.DailySummaryFacts.SourceMessage;
import dev.qqbot.schedule.DailySummaryService.Snapshot.Header;
import dev.qqbot.text.Texts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DailySummaryService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_HISTORY = 100;
    private final Db db;
    private final Clock clock;
    private final DailySummaryPreparation preparation;

    /** 创建使用固定时钟的日报服务。 */
    public DailySummaryService(Db db, Clock clock) {
        this(db, clock, null);
    }

    /** 创建日报服务，接入部署配置后在 QQ 覆盖元数据中记录采集范围说明。 */
    @Autowired
    public DailySummaryService(Db db, Clock clock, BotProperties properties) {
        this.db = Objects.requireNonNull(db, "db must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.preparation = new DailySummaryPreparation(db, clock, properties != null);
    }

    /** 扫描到期计划并为前一业务日提交日报作业。 */
    @Scheduled(fixedDelay = 10_000)
    public void scheduleDue() {
        scheduleDueJobs();
    }

    /** 按触发方式提交前一业务日的日报；群须启用采集与回复。 */
    public UUID trigger(UUID groupId, String mode) {
        return create(groupId, LocalDate.now(clock.withZone(ZONE)).minusDays(1), mode);
    }

    /** 创建指定日期的日报，或复用群内当天已有的日报。 */
    public UUID create(UUID groupId, LocalDate date, String mode) {
        return create(new Request(groupId, date, mode));
    }

    /** 重试失败的分析或投递；已失效及投递结果未知的作业拒绝重试。 */
    public Map<String, Object> retry(UUID id) {
        return retryTransaction(Objects.requireNonNull(id, "id must not be null"));
    }

    /** 冻结并认领待执行日报。终态或无可用输入时返回空结果。 */
    public Optional<Snapshot> claim(UUID id) {
        return preparation.claim(Objects.requireNonNull(id, "id must not be null"));
    }

    /** 原子发布已校验的日报正文与待投递分段。展示校验失败时作业和分段均不写入。 */
    public void finish(Completion completion) {
        publish(Objects.requireNonNull(completion, "completion must not be null"));
    }

    void finish(Snapshot snapshot, String report) {
        finish(
                new Completion(
                        snapshot.header(),
                        snapshot.sourceIds(),
                        snapshot.coverage().json(),
                        report));
    }

    /** 按业务日期倒序读取指定群的日报，结果限制在 1 至 100 项。 */
    public List<Map<String, Object>> summaries(UUID groupId, int limit) {
        Objects.requireNonNull(groupId, "groupId must not be null");
        return db.list(
                """
                select id,business_date,state,attempt,coverage,report,error_code,created_at,finished_at
                from daily_run where group_id=? order by business_date desc limit ?
                """,
                groupId,
                Math.max(1, Math.min(limit, MAX_HISTORY)));
    }

    public record Completion(
            Header header, List<String> sourceIds, String coverageJson, String report) {
        public Completion {
            Objects.requireNonNull(header, "header must not be null");
            sourceIds = List.copyOf(sourceIds);
            Objects.requireNonNull(coverageJson, "coverageJson must not be null");
            Objects.requireNonNull(report, "report must not be null");
        }
    }

    private static final String SCHEDULE = "SCHEDULE";
    private static final String QUEUED = "QUEUED";
    private static final String GENERATED = "GENERATED";
    private static final String DELIVERY_FAILED = "DELIVERY_FAILED";
    private static final Set<String> CAN_RETRY_ANALYSIS = Set.of("FAILED", "INTERRUPTED");
    private static final Set<String> IN_PROGRESS = Set.of(QUEUED, "RUNNING", GENERATED);
    private static final int POLL_LIMIT = 20;

    private void scheduleDueJobs() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        for (Due due : readDueSchedules(now)) {
            db.tx(() -> scheduleInTransaction(due, now, today));
        }
    }

    private List<Due> readDueSchedules(Instant now) {
        return db.query(
                """
                select s.group_id,s.local_time from daily_schedule s join group_config g on g.id=s.group_id
                where s.enabled and g.enabled and g.reply_enabled and g.chat_type='GROUP' and s.zone=?
                  and s.next_run_at<=? limit ?
                """,
                DailySummaryService::readDue,
                ZONE.getId(),
                now,
                POLL_LIMIT);
    }

    private static Due readDue(ResultSet row, int rowNumber) throws SQLException {
        return new Due(
                UUID.fromString(row.getString("group_id")),
                LocalTime.parse(row.getString("local_time")));
    }

    private Void scheduleInTransaction(Due due, Instant now, LocalDate today) {
        var request = new Request(due.group(), today.minusDays(1), SCHEDULE);
        Instant next = today.plusDays(1).atTime(due.time()).atZone(ZONE).toInstant();
        // SIDE EFFECT: 作业入队和下一次调度时间由同一事务提交。
        createInTransaction(request);
        db.exec(
                "update daily_schedule set next_run_at=? where group_id=? and next_run_at<=?",
                next,
                due.group(),
                now);
        return null;
    }

    private UUID create(Request request) {
        return db.tx(() -> createInTransaction(request));
    }

    private UUID createInTransaction(Request request) {
        Group group = requireGroup(request.group());
        var previous =
                db.find(
                        "select id,group_id,epoch,state from daily_run where group_id=? and business_date=?",
                        DailySummaryService::readJob,
                        request.group(),
                        request.businessDate());
        if (previous.isPresent()) {
            return reuseJob(previous.orElseThrow(), group);
        }
        return insertJob(request, group.epoch());
    }

    private UUID reuseJob(Job job, Group group) {
        if (CAN_RETRY_ANALYSIS.contains(job.state()) || DELIVERY_FAILED.equals(job.state())) {
            retryExisting(group, job);
        }
        return job.id();
    }

    private UUID insertJob(Request request, long epoch) {
        UUID id = UUID.randomUUID();
        // SIDE EFFECT: 调用方在事务内完成群状态检查与同日去重。
        db.exec(
                """
                insert into daily_run(id,group_id,business_date,epoch,trigger_type,state)
                values(?,?,?,?,?,'QUEUED')
                """,
                id,
                request.group(),
                request.businessDate(),
                epoch,
                request.trigger());
        return id;
    }

    private Map<String, Object> retryTransaction(UUID id) {
        RetryResult result = db.tx(() -> retryInTransaction(id));
        return Map.of("runId", id, "state", result.state());
    }

    private RetryResult retryInTransaction(UUID id) {
        var job =
                db.find(
                                "select id,group_id,epoch,state from daily_run where id=?",
                                DailySummaryService::readJob,
                                id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "日报任务不存在，请刷新执行记录"));
        return retryExisting(requireGroup(job.group()), job);
    }

    private RetryResult retryExisting(Group group, Job job) {
        if (group.epoch() != job.epoch()) {
            throw conflict("来源记录已失效，不能重试旧日报");
        }
        if (IN_PROGRESS.contains(job.state())) {
            return new RetryResult(job.state());
        }
        RetryResult result = planRetry(job);
        // SIDE EFFECT: 投递回排、作业状态和审计覆盖在同一事务内。
        if (GENERATED.equals(result.state())) {
            resetDelivery(job.id());
        }
        db.exec(
                "update daily_run set state=?,attempt=attempt+1,error_code=null,finished_at=null where id=?",
                result.state(),
                job.id());
        db.audit("SUMMARY_RETRY", job.id());
        return result;
    }

    private RetryResult planRetry(Job job) {
        if (CAN_RETRY_ANALYSIS.contains(job.state())) {
            return new RetryResult(QUEUED);
        }
        if (DELIVERY_FAILED.equals(job.state())) {
            verifyDeliveryCanRetry(job.id());
            return new RetryResult(GENERATED);
        }
        if ("DELIVERY_UNKNOWN".equals(job.state())) {
            throw conflict("发送结果未知，请先核实群内是否收到，不能直接重试以免重复发送");
        }
        throw conflict("当前任务状态不支持重试：" + job.state());
    }

    private void verifyDeliveryCanRetry(UUID job) {
        boolean hasPending =
                db.find(
                                "select id from outbox where daily_run_id=? and state in ('UNKNOWN','SENDING') limit 1",
                                (row, number) -> true,
                                job)
                        .isPresent();
        if (hasPending) {
            throw conflict("存在发送中或结果未知的分段，请先核实发送结果");
        }
    }

    private void resetDelivery(UUID job) {
        int reset =
                db.exec(
                        """
                update outbox set state='PENDING',attempts=0,error_code=null,
                  next_attempt_at=cast(unixepoch('subsec')*1000 as integer)
                where daily_run_id=? and (state='FAILED' or (state='CANCELLED' and error_code='PREVIOUS_PART_FAILED'))
                """,
                        job);
        if (reset == 0) {
            throw conflict("没有可补发的失败分段，请刷新执行记录");
        }
    }

    private Group requireGroup(UUID id) {
        Group group =
                db.find(
                                "select epoch,enabled,reply_enabled,chat_type from group_config where id=?",
                                DailySummaryService::readGroup,
                                id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "群配置不存在，请刷新群列表"));
        if (group.isPrivate()) {
            throw conflict("私聊不支持群日报");
        }
        if (!group.isEnabled()) {
            throw conflict("请先启用群文本采集，再执行日报");
        }
        if (!group.hasReplies()) {
            throw conflict("请先启用机器人回复，再执行日报");
        }
        return group;
    }

    private static Group readGroup(ResultSet row, int rowNumber) throws SQLException {
        return new Group(
                row.getLong("epoch"),
                row.getBoolean("enabled"),
                row.getBoolean("reply_enabled"),
                "PRIVATE".equals(row.getString("chat_type")));
    }

    private static Job readJob(ResultSet row, int rowNumber) throws SQLException {
        return new Job(
                UUID.fromString(row.getString("id")),
                UUID.fromString(row.getString("group_id")),
                row.getLong("epoch"),
                row.getString("state"));
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record Request(UUID group, LocalDate businessDate, String trigger) {
        Request {
            Objects.requireNonNull(group, "group must not be null");
            Objects.requireNonNull(businessDate, "businessDate must not be null");
            Objects.requireNonNull(trigger, "trigger must not be null");
        }
    }

    private record Group(long epoch, boolean isEnabled, boolean hasReplies, boolean isPrivate) {}

    private record Job(UUID id, UUID group, long epoch, String state) {}

    private record RetryResult(String state) {}

    private record Due(UUID group, LocalTime time) {}

    private void publish(Completion completion) {
        String readable = Texts.plainText(completion.report());
        if (readable.isBlank()) {
            throw new IllegalStateException("EMPTY_REPORT");
        }
        db.tx(() -> finishInTransaction(completion, readable));
    }

    private Void finishInTransaction(Completion completion, String readable) {
        Header header = completion.header();
        var date = findRunningDate(header.id());
        if (date.isEmpty()) {
            return null;
        }
        if (!isScopeCurrent(header)) {
            cancelStale(header.id());
            return null;
        }
        var coverage = DailySummaryCoverage.decode(db, completion.coverageJson());
        List<String> parts =
                DailySummaryPresentation.render(readable, date.orElseThrow(), coverage);
        // SIDE EFFECT: 状态与全部分段由同一个 SQLite 事务提交或回滚。
        saveReport(completion, readable);
        enqueueParts(header, parts);
        return null;
    }

    private Optional<LocalDate> findRunningDate(UUID id) {
        return db.find(
                "select business_date from daily_run where id=? and state='RUNNING'",
                (row, number) -> LocalDate.parse(row.getString(1)),
                id);
    }

    private boolean isScopeCurrent(Header header) {
        return db.find(
                        "select id from group_config where id=? and epoch=? and enabled and reply_enabled",
                        (row, number) -> true,
                        header.group(),
                        header.epoch())
                .isPresent();
    }

    private void cancelStale(UUID id) {
        db.exec(
                """
                update daily_run set state='CANCELLED',error_code='STALE_EPOCH',
                  finished_at=cast(unixepoch('subsec')*1000 as integer) where id=?
                """,
                id);
    }

    private void saveReport(Completion completion, String readable) {
        db.exec(
                """
                update daily_run set state='GENERATED',report=?,coverage=?,source_ids=?,
                  finished_at=cast(unixepoch('subsec')*1000 as integer) where id=?
                """,
                readable,
                completion.coverageJson(),
                db.json(completion.sourceIds()),
                completion.header().id());
    }

    private void enqueueParts(Header header, List<String> parts) {
        String key = "daily:" + header.id();
        for (int index = 0; index < parts.size(); index++) {
            db.exec(
                    """
                    insert into outbox(id,group_id,result_key,daily_run_id,epoch,part_index,body)
                    values(?,?,?,?,?,?,?) on conflict(result_key,part_index) do nothing
                    """,
                    UUID.randomUUID(),
                    header.group(),
                    key,
                    header.id(),
                    header.epoch(),
                    index,
                    parts.get(index));
        }
    }

    public record Snapshot(Header header, Material material, DailySummaryCoverage coverage) {
        /** 按生成输入顺序列出冻结来源 ID。 */
        public List<String> sourceIds() {
            return material.messages().stream().map(SourceMessage::messageId).toList();
        }

        public record Header(UUID id, UUID group, long epoch, LocalDate date) {}

        public record Material(List<SourceMessage> messages, Map<String, String> memberNames) {
            public Material {
                messages = List.copyOf(messages);
                memberNames = Map.copyOf(memberNames);
            }
        }
    }
}
