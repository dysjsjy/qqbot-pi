/**
 * [INPUT]: 依赖群策略、活跃席位、作业队列和画像作业就绪规则。
 * [OUTPUT]: 按群读取候选，事务内认领单份带版本的画像作业，记录失败与回排。
 * [POS]: 调度的 SQLite 边界。调用方串行认领，模型在认领事务提交后运行。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.memory.MemberProfileEvidence.Progress;
import dev.qqbot.memory.MemberProfileExtractor.Cycle;
import dev.qqbot.memory.MemberProfileExtractor.Job;
import dev.qqbot.memory.MemberProfileExtractor.JobType;
import dev.qqbot.memory.MemberProfileExtractor.Work;
import dev.qqbot.memory.MemberProfilePolicyService.Policy;
import dev.qqbot.memory.MemberProfilePolicyService.Scope;
import dev.qqbot.memory.MemberProfileService.Submission;
import dev.qqbot.persistence.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class MemberProfileJobs {
    private static final Duration SLOT_REFRESH = Duration.ofMinutes(15);
    private static final String SERVICE_RESTART = "SERVICE_RESTART";
    private static final String FAILED = "FAILED";
    private static final String PROFILE_DAILY_BUDGET_EXHAUSTED = "PROFILE_DAILY_BUDGET_EXHAUSTED";
    private final Db db;
    private final MemberProfileStats stats;
    private final MemberProfileService service;
    private final MemberProfileExtractor extractor;
    private final Map<UUID, Scan> scans = new HashMap<>();

    MemberProfileJobs(
            Db db,
            MemberProfileStats stats,
            MemberProfileService service,
            MemberProfileExtractor extractor) {
        this.db = db;
        this.stats = stats;
        this.service = service;
        this.extractor = extractor;
    }

    List<UUID> readEnabledGroups() {
        return db.query(
                """
                select g.id from group_config g join member_profile_policy q on q.group_id=g.id
                where g.enabled and g.chat_type='GROUP' and q.enabled
                order by coalesce((select max(j.finished_at) from member_profile_job j
                  where j.group_id=g.id and j.epoch=g.epoch),0),g.id
                """,
                (row, number) -> UUID.fromString(row.getString("id")));
    }

    void pruneScans(List<UUID> enabledGroups) {
        scans.keySet().retainAll(enabledGroups);
    }

    Optional<Work> claim(UUID group, Instant now, MemberProfilePolicyService policies) {
        return db.tx(() -> claimInTransaction(group, now, policies));
    }

    private Optional<Work> claimInTransaction(
            UUID group, Instant now, MemberProfilePolicyService policies) {
        Scope scope = policies.scope(group);
        Policy policy = policies.policy(group);
        if (!scope.collecting()
                || !policy.enabled()
                || policies.remaining(group, policy, now) == 0) {
            return Optional.empty();
        }
        var cycle = new Cycle(scope, policy, now);
        // SIDE EFFECT: 席位、失效作业和认领状态在同一事务内更新。
        refreshSlots(cycle);
        cancelIneligible(cycle);
        return findQueued(scope)
                .or(() -> enqueueAutomatic(cycle))
                .flatMap(job -> claimQueued(cycle, job));
    }

    private void refreshSlots(Cycle cycle) {
        UUID group = cycle.scope().group();
        Scan scan = scans.get(group);
        if (scan == null || !scan.isCurrent(cycle)) {
            stats.refresh(cycle.scope(), cycle.policy(), cycle.now());
            scans.put(
                    group,
                    new Scan(
                            cycle.scope().epoch(),
                            cycle.policy().revision(),
                            cycle.now().plus(SLOT_REFRESH)));
        }
    }

    private void cancelIneligible(Cycle cycle) {
        db.exec(
                """
                update member_profile_job as j set state='CANCELLED',error_code='PROFILE_NOT_ELIGIBLE',finished_at=?
                where j.group_id=? and j.epoch=? and j.state='QUEUED' and not exists(
                  select 1 from member_profile p where p.id=j.profile_id
                  and p.active_slot and not p.opted_out and not p.locked_by_admin)
                """,
                cycle.now(),
                cycle.scope().group(),
                cycle.scope().epoch());
    }

    private Optional<Job> findQueued(Scope scope) {
        return db.find(
                """
                select id,job_type,profile_id,sender_id from member_profile_job
                where group_id=? and epoch=? and state='QUEUED'
                order by case when trigger_type='AUTO' then 1 else 0 end,created_at,id limit 1
                """,
                MemberProfileJobs::readJob,
                scope.group(),
                scope.epoch());
    }

    private static Job readJob(ResultSet row, int rowNumber) throws SQLException {
        return new Job(
                UUID.fromString(row.getString("id")),
                JobType.valueOf(row.getString("job_type")),
                UUID.fromString(row.getString("profile_id")),
                row.getString("sender_id"));
    }

    private Optional<Work> claimQueued(Cycle cycle, Job job) {
        var member =
                db.find(
                        "select * from member_profile where group_id=? and epoch=? and sender_id=?",
                        Snapshot::readMember,
                        cycle.scope().group(),
                        cycle.scope().epoch(),
                        job.sender());
        if (member.isEmpty()) {
            return Optional.empty();
        }
        return member.flatMap(snapshot -> start(new Work(job, snapshot, cycle)));
    }

    private Optional<Work> start(Work work) {
        int claimed =
                db.exec(
                        "update member_profile_job set state='RUNNING',error_code=null where id=? and state='QUEUED'",
                        work.id());
        return claimed == 1 ? Optional.of(work) : Optional.empty();
    }

    private Optional<Job> enqueueAutomatic(Cycle cycle) {
        for (Snapshot member : readCandidates(cycle.scope())) {
            if (isCoolingDown(cycle, member.identity().id())) {
                continue;
            }
            var job =
                    selectJobType(cycle, member)
                            .flatMap(type -> enqueue(cycle, member.identity(), type));
            if (job.isPresent()) {
                return job;
            }
        }
        return Optional.empty();
    }

    private List<Snapshot> readCandidates(Scope scope) {
        return db.query(
                """
                select p.* from member_profile p where p.group_id=? and p.epoch=?
                  and p.active_slot and not p.opted_out and not p.locked_by_admin
                order by p.needs_reflection desc,p.pending_confirmed_changes desc,p.last_profiled_at is not null,
                  cast(json_extract(p.l0_stats_json,'$.messages30d') as integer) desc,
                  p.last_profiled_at,p.last_active_at desc,p.id
                """,
                Snapshot::readMember,
                scope.group(),
                scope.epoch());
    }

    private boolean isCoolingDown(Cycle cycle, UUID profile) {
        return db.find(
                        """
                        select state,error_code,finished_at from member_profile_job
                        where profile_id=? order by created_at desc,id desc limit 1
                        """,
                        MemberProfileJobs::readPrevious,
                        profile)
                .filter(previous -> previous.blocksScheduling(cycle))
                .isPresent();
    }

    private static Previous readPrevious(ResultSet row, int rowNumber) throws SQLException {
        return new Previous(
                row.getString("state"),
                row.getString("error_code"),
                Snapshot.readInstant(row, "finished_at"));
    }

    private Optional<JobType> selectJobType(Cycle cycle, Snapshot member) {
        if (MemberProfileExtractor.reflectionDue(member, cycle.now())) {
            return Optional.of(JobType.REFLECT);
        }
        return extractor.due(member, cycle) ? Optional.of(JobType.EXTRACT) : Optional.empty();
    }

    private Optional<Job> enqueue(Cycle cycle, Snapshot.Identity member, JobType type) {
        var submission = new Submission(member.id(), member.sender(), type.name(), "AUTO", 1);
        return service.queue(cycle.scope(), submission, cycle.now())
                .map(id -> new Job(id, type, member.id(), member.sender()));
    }

    void recover(Instant now) {
        db.exec(
                "update member_profile_job set state=?,error_code=?,finished_at=? where state='RUNNING'",
                FAILED,
                SERVICE_RESTART,
                now);
    }

    static boolean isDeferred(String error) {
        return "BACKGROUND_BUSY".equals(error) || PROFILE_DAILY_BUDGET_EXHAUSTED.equals(error);
    }

    void requeue(UUID id, String error) {
        db.exec(
                "update member_profile_job set state='QUEUED',error_code=? where id=? and state='RUNNING'",
                error,
                id);
    }

    void finish(UUID id, String error, Instant now, boolean isCancelled) {
        db.exec(
                "update member_profile_job set state=?,error_code=?,finished_at=? where id=? and state='RUNNING'",
                isCancelled ? "CANCELLED" : FAILED,
                error,
                now,
                id);
    }

    private record Scan(long epoch, long revision, Instant next) {
        boolean isCurrent(Cycle cycle) {
            return epoch == cycle.scope().epoch()
                    && revision == cycle.policy().revision()
                    && next.isAfter(cycle.now());
        }
    }

    private record Previous(String state, String error, Instant finishedAt) {
        boolean blocksScheduling(Cycle cycle) {
            return FAILED.equals(state)
                    && (SERVICE_RESTART.equals(error)
                            || finishedAt
                                    .plus(Duration.ofHours(cycle.policy().updateCooldownHours()))
                                    .isAfter(cycle.now()));
        }
    }

    record Snapshot(Identity identity, Progress progress, Reflection reflection) {
        static Snapshot readMember(ResultSet row, int rowNumber) throws SQLException {
            var identity =
                    new Identity(
                            UUID.fromString(row.getString("id")),
                            row.getString("sender_id"),
                            row.getLong("version"));
            return new Snapshot(identity, readProgress(row), readReflection(row));
        }

        private static Progress readProgress(ResultSet row) throws SQLException {
            long replayThrough = row.getLong("replay_through_seq");
            Long replayEnd = row.wasNull() ? null : replayThrough;
            return new Progress(
                    row.getLong("last_profiled_seq"),
                    replayEnd,
                    readInstant(row, "last_profiled_at"),
                    row.getLong("source_floor_seq"));
        }

        private static Reflection readReflection(ResultSet row) throws SQLException {
            return new Reflection(
                    row.getString("l0_stats_json"),
                    row.getString("admin_note"),
                    row.getBoolean("needs_reflection"),
                    new ReflectionProgress(
                            row.getLong("pending_confirmed_changes"),
                            readInstant(row, "last_reflected_at")));
        }

        static Instant readInstant(ResultSet row, String column) throws SQLException {
            long millis = row.getLong(column);
            return row.wasNull() ? null : Instant.ofEpochMilli(millis);
        }

        record Identity(UUID id, String sender, long version) {}

        record Reflection(
                String stats, String note, boolean needsReflection, ReflectionProgress progress) {}

        record ReflectionProgress(long changes, Instant lastReflectedAt) {}
    }
}
