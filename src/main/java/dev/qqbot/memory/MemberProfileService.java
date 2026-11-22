/**
 * [INPUT]: 依赖群策略、L0 席位、SQLite 与原文留存。
 * [OUTPUT]: 返回当前群画像与证据，事务提交人工纠偏、参与设置和作业。
 * [POS]: HTTP 与群命令共用；模型生成仅由后台作业执行。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static dev.qqbot.memory.MemberProfilePolicyService.*;
import static dev.qqbot.memory.MemberProfileStats.time;

import dev.qqbot.RetentionProperties;
import dev.qqbot.memory.MemberProfileStats.StoredMember;
import dev.qqbot.persistence.Db;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberProfileService {
    private static final int MAX_SENDER_LENGTH = 160;
    private static final String COMMAND_STATUS = "status";
    private static final String COMMAND_OFF = "off";
    private static final String COMMAND_ON = "on";
    private static final String COMMAND_CLEAR = "clear";
    private static final Set<String> SELF_COMMANDS =
            Set.of(COMMAND_STATUS, COMMAND_OFF, COMMAND_ON, COMMAND_CLEAR);
    private static final String NOT_ENABLED = "本群未启用实验性成员画像。";
    private static final Set<String> STATES =
            Set.of("ACTIVE", "DORMANT", "LOCKED", "OPTED_OUT", "PENDING");
    private static final String VIEW =
            """
        with members as (select p.*,
          case when p.opted_out then 'OPTED_OUT' when p.locked_by_admin then 'LOCKED'
               when not p.active_slot then 'DORMANT' when p.profile_card='' then 'PENDING' else 'ACTIVE' end as profile_state,
          (select count(*) from member_profile_observation o where o.profile_id=p.id and o.state='CONFIRMED' and o.expires_at>?) as confirmed_count,
          (select count(*) from member_profile_observation o where o.profile_id=p.id and o.state='CANDIDATE' and o.expires_at>?) as candidate_count
        from member_profile p where p.group_id=? and p.epoch=? and (instr(lower(p.display_name),lower(?))>0 or instr(lower(p.sender_id),lower(?))>0))
        """;
    private final Db db;
    private final Clock clock;
    private final RetentionProperties retention;
    private final MemberProfilePolicyService policies;
    private final MemberProfileStats stats;

    public MemberProfileService(
            Db db,
            Clock clock,
            RetentionProperties retention,
            MemberProfilePolicyService policies,
            MemberProfileStats stats) {
        this.db = db;
        this.clock = clock;
        this.retention = retention;
        this.policies = policies;
        this.stats = stats;
    }

    public record Edit(
            String senderId,
            String adminNote,
            String profileCard,
            boolean lockedByAdmin,
            boolean optedOut,
            Long version) {}

    public record Refresh(String senderId, String mode) {}

    public record Page(List<Member> members, long total) {}

    public record Member(
            UUID id,
            String senderId,
            String displayName,
            List<?> aliases,
            Map<?, ?> stats,
            String profileCard,
            String adminNote,
            String state,
            boolean lockedByAdmin,
            boolean optedOut,
            long version,
            long confirmedCount,
            long candidateCount,
            Instant lastActiveAt) {}

    public record Observation(
            UUID id,
            String category,
            String statement,
            String state,
            double confidence,
            long evidenceCount,
            long distinctDays,
            long sourceExpiredCount,
            Instant expiresAt,
            List<Source> sources) {}

    public record Source(UUID id, Instant occurredAt, String excerpt) {}

    public record Interaction(
            String senderId, long replyCount, long mentionCount, Instant lastInteractionAt) {}

    public record Detail(
            Member member, List<Observation> observations, List<Interaction> interactions) {}

    public Page list(UUID group, String state, String search, int limit, int offset) {
        if (limit < 1
                || limit > 100
                || offset < 0
                || search.length() > 200
                || (!state.isEmpty() && !STATES.contains(state))) {
            throw bad("INVALID_PROFILE_QUERY");
        }
        return db.tx(
                () -> {
                    var scope = policies.scope(group);
                    Instant now = clock.instant();
                    String filter = " from members where (?='' or profile_state=?)";
                    Object[] args = {
                        now, now, group, scope.epoch(), search.strip(), search.strip(), state, state
                    };
                    long total = count(db.one(VIEW + "select count(*) as n" + filter, args), "n");
                    var page = new ArrayList<>(Arrays.asList(args));
                    page.add(limit);
                    page.add(offset);
                    var members =
                            db.list(
                                    VIEW
                                            + "select *"
                                            + filter
                                            + " order by active_slot desc,last_active_at desc,id limit ? offset ?",
                                    page.toArray());
                    return new Page(members.stream().map(this::member).toList(), total);
                });
    }

    public Detail detail(UUID group, String sender) {
        validateSender(sender);
        return db.tx(() -> detail(policies.scope(group), sender, clock.instant()));
    }

    private Detail detail(Scope scope, String sender, Instant now) {
        var row =
                db.one(
                        VIEW + "select * from members where sender_id=?",
                        now,
                        now,
                        scope.group(),
                        scope.epoch(),
                        "",
                        "",
                        sender);
        if (row == null) {
            throw missing("groupId=" + scope.group() + ", senderId=" + sender);
        }
        var observations =
                db
                        .list(
                                "select * from member_profile_observation where profile_id=? order by last_reinforced_at desc,id",
                                row.get("id"))
                        .stream()
                        .map(value -> observation(scope, sender, value, now))
                        .toList();
        var interactions =
                db
                        .list(
                                """
            select to_sender_id,reply_count_30d,mention_count_30d,last_interaction_at from group_social_edge where group_id=? and epoch=? and from_sender_id=?
            order by reply_count_30d+mention_count_30d desc,last_interaction_at desc,to_sender_id limit 5
            """,
                                scope.group(),
                                scope.epoch(),
                                sender)
                        .stream()
                        .map(
                                edge ->
                                        new Interaction(
                                                (String) edge.get("to_sender_id"),
                                                count(edge, "reply_count_30d"),
                                                count(edge, "mention_count_30d"),
                                                time(edge, "last_interaction_at")))
                        .toList();
        return new Detail(member(row), observations, interactions);
    }

    private Member member(Map<String, Object> row) {
        return new Member(
                (UUID) row.get("id"),
                (String) row.get("sender_id"),
                (String) row.get("display_name"),
                db.parse((String) row.get("aliases_json"), List.class),
                db.parse((String) row.get("l0_stats_json"), Map.class),
                (String) row.get("profile_card"),
                (String) row.get("admin_note"),
                (String) row.get("profile_state"),
                flag(row, "locked_by_admin"),
                flag(row, "opted_out"),
                count(row, "version"),
                count(row, "confirmed_count"),
                count(row, "candidate_count"),
                time(row, "last_active_at"));
    }

    private Observation observation(
            Scope scope, String sender, Map<String, Object> row, Instant now) {
        Instant expires = time(row, "expires_at");
        String state = (String) row.get("state");
        if (Set.of("CANDIDATE", "CONFIRMED").contains(state) && !expires.isAfter(now)) {
            state = "EXPIRED";
        }
        var sources =
                db
                        .list(
                                """
            select m.id,m.occurred_at,m.body from json_each(?) s join group_message m on m.id=s.value
            where m.group_id=? and m.epoch=? and m.sender_id=? and m.kind='TEXT' and m.occurred_at between ? and ? order by m.occurred_at,m.id
            """,
                                row.get("source_message_ids_json"),
                                scope.group(),
                                scope.epoch(),
                                sender,
                                retention.archiveCutoff(now),
                                now)
                        .stream()
                        .map(
                                source ->
                                        new Source(
                                                (UUID) source.get("id"),
                                                time(source, "occurred_at"),
                                                ProfileGuardrails.input(
                                                        (String) source.get("body"))))
                        .toList();
        return new Observation(
                (UUID) row.get("id"),
                (String) row.get("category"),
                (String) row.get("statement"),
                state,
                ((Number) row.get("confidence")).doubleValue(),
                count(row, "evidence_count"),
                count(row, "distinct_days"),
                count(row, "source_expired_count"),
                expires,
                sources);
    }

    public Detail edit(UUID group, Edit input) {
        Objects.requireNonNull(input, "edit must not be null");
        validateSender(input.senderId());
        if (input.version() == null || input.version() < 0) {
            throw bad("PROFILE_VERSION_REQUIRED");
        }
        String note = ProfileGuardrails.cleanField(input.adminNote(), ProfileGuardrails.MAX_CARD);
        String card =
                input.profileCard() == null
                        ? null
                        : ProfileGuardrails.cleanField(
                                input.profileCard(), ProfileGuardrails.MAX_CARD);
        return db.tx(() -> editMember(group, input, note, card));
    }

    private Detail editMember(UUID group, Edit input, String note, String card) {
        Scope scope = policies.scope(group);
        Instant now = clock.instant();
        StoredMember current = requireMember(scope, input.senderId());
        if (input.version() != current.version()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "PROFILE_VERSION_CONFLICT",
                    new IllegalStateException(
                            "profileId="
                                    + current.id()
                                    + ", expected="
                                    + current.version()
                                    + ", actual="
                                    + input.version()));
        }

        // SIDE EFFECT: 纠偏与清除水位在同一事务内写入，避免旧证据恢复已退出的画像。
        if (input.optedOut() || current.isOptedOut()) {
            MemberProfileLifecycle.clearMember(
                    db, scope, current.id(), input.senderId(), input.optedOut(), now);
        }
        if (!input.optedOut()) {
            boolean hasChangedNote = !Objects.equals(note, current.adminNote());
            db.exec(
                    """
                    update member_profile set admin_note=?,profile_card=coalesce(?,profile_card),
                        locked_by_admin=?,opted_out=0,
                        needs_reflection=case when ? and not ? then 1 else needs_reflection end,
                        version=version+1,updated_at=? where id=?
                    """,
                    note,
                    card,
                    input.lockedByAdmin(),
                    hasChangedNote,
                    input.lockedByAdmin(),
                    now,
                    current.id());
            if (input.lockedByAdmin()) {
                cancelJobs(current.id(), now, "PROFILE_LOCKED");
            }
        }
        db.audit(
                input.optedOut() ? "MEMBER_PROFILE_ADMIN_OPT_OUT" : "MEMBER_PROFILE_EDIT",
                group,
                Map.of("profileId", current.id()));
        return detail(scope, input.senderId(), now);
    }

    public void clear(UUID group, String sender) {
        validateSender(sender);
        db.tx(() -> clearMember(group, sender));
    }

    private Void clearMember(UUID group, String sender) {
        Scope scope = policies.scope(group);
        StoredMember member = requireMember(scope, sender);
        // SIDE EFFECT: 清除内容不改变参与设置，同时登记审计。
        MemberProfileLifecycle.clearMember(
                db, scope, member.id(), sender, member.isOptedOut(), clock.instant());
        db.audit("MEMBER_PROFILE_ADMIN_CLEAR", group, Map.of("profileId", member.id()));
        return null;
    }

    public void deleteObservation(UUID group, UUID observation) {
        Objects.requireNonNull(observation, "observationId must not be null");
        db.tx(() -> removeObservation(group, observation));
    }

    private Void removeObservation(UUID group, UUID observation) {
        Scope scope = policies.scope(group);
        Instant now = clock.instant();
        UUID profile =
                db.find(
                                """
                select profile_id from member_profile_observation
                where group_id=? and epoch=? and id=? and state<>'DELETED'
                """,
                                (row, index) -> UUID.fromString(row.getString(1)),
                                group,
                                scope.epoch(),
                                observation)
                        .orElseThrow(
                                () ->
                                        missing(
                                                "groupId="
                                                        + group
                                                        + ", observationId="
                                                        + observation));

        // SIDE EFFECT: 删除观测时同时撤销基于该观测生成的卡片。
        db.exec(
                """
                update member_profile_observation
                set state='DELETED',statement='',source_message_ids_json='[]',updated_at=? where id=?
                """,
                now,
                observation);
        db.exec(
                """
                update member_profile set needs_reflection=1,profile_card='',confidence_score=0,
                    version=version+1,updated_at=? where id=?
                """,
                now,
                profile);
        db.audit("MEMBER_PROFILE_OBSERVATION_DELETE", group, Map.of("observationId", observation));
        return null;
    }

    public List<UUID> refresh(UUID group, Refresh input) {
        Objects.requireNonNull(input, "refresh must not be null");
        String kind =
                switch (Objects.toString(input.mode(), "")) {
                    case "INCREMENTAL" -> "EXTRACT";
                    case "REPLAY" -> "REPLAY";
                    default -> throw bad("INVALID_PROFILE_REFRESH_MODE");
                };
        if (input.senderId() != null) {
            validateSender(input.senderId());
        }
        return db.tx(() -> refreshMembers(group, input.senderId(), kind));
    }

    private List<UUID> refreshMembers(UUID group, String sender, String kind) {
        Scope scope = policies.scope(group);
        Instant now = clock.instant();
        Policy policy = requireAvailablePolicy(scope, now);

        // SIDE EFFECT: 刷新可创建画像。指定成员不存在或忙碌时，事务回滚席位与提交记录。
        stats.refresh(
                scope,
                policy,
                new MemberProfileStats.RefreshRequest(now, kind.equals("REPLAY"), sender));
        if (sender != null) {
            requireMember(scope, sender);
        }
        var submissions = manualSubmissions(scope, sender, kind, policy.maxProfiles());
        var ids = new ArrayList<UUID>();
        for (Submission submission : submissions) {
            var queued = queue(scope, submission, now);
            if (queued.isPresent()) {
                ids.add(queued.orElseThrow());
            }
        }
        if (sender != null && ids.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "PROFILE_NOT_ELIGIBLE_OR_BUSY",
                    new IllegalStateException("groupId=" + group + ", senderId=" + sender));
        }
        db.audit("MEMBER_PROFILE_REFRESH", group, Map.of("mode", kind, "jobs", ids.size()));
        return List.copyOf(ids);
    }

    private List<Submission> manualSubmissions(Scope scope, String sender, String kind, int limit) {
        return db.query(
                """
                select id,sender_id from member_profile
                where group_id=? and epoch=? and active_slot and not opted_out and not locked_by_admin
                    and (? is null or sender_id=?) order by last_active_at desc,id limit ?
                """,
                (row, index) ->
                        new Submission(
                                UUID.fromString(row.getString("id")),
                                row.getString("sender_id"),
                                kind,
                                "MANUAL",
                                1),
                scope.group(),
                scope.epoch(),
                sender,
                sender,
                limit);
    }

    public List<Map<String, Object>> jobs(UUID group, int limit) {
        if (limit < 1 || limit > 100) {
            throw bad("INVALID_PROFILE_QUERY");
        }
        Scope scope = policies.scope(group);
        return db.list(
                "select id,sender_id,job_type,trigger_type,state,attempt,from_seq,to_seq,source_count,error_code,created_at,finished_at from member_profile_job where group_id=? and epoch=? order by created_at desc,id limit ?",
                group,
                scope.epoch(),
                limit);
    }

    public UUID retry(UUID group, UUID job) {
        Objects.requireNonNull(job, "jobId must not be null");
        return db.tx(() -> retryJob(group, job));
    }

    private UUID retryJob(UUID group, UUID job) {
        Scope scope = policies.scope(group);
        Instant now = clock.instant();
        requireAvailablePolicy(scope, now);
        Submission submission = requireRetryableJob(scope, job);

        // SIDE EFFECT: 只有新作业入队成功才关闭旧作业；两步与审计共同提交。
        UUID id =
                queue(scope, submission, now)
                        .orElseThrow(() -> retryConflict("PROFILE_JOB_BUSY", scope, job));
        db.exec(
                "update member_profile_job set state='CANCELLED',error_code='PROFILE_JOB_RETRIED' where id=?",
                job);
        db.audit("MEMBER_PROFILE_JOB_RETRY", group, Map.of("jobId", job));
        return id;
    }

    private Submission requireRetryableJob(Scope scope, UUID job) {
        return db.find(
                        """
                select p.id,j.sender_id,j.job_type,j.attempt from member_profile_job j
                join member_profile p on p.id=j.profile_id
                where j.id=? and j.group_id=? and j.epoch=? and j.state='FAILED' and j.attempt<3
                    and p.active_slot and not p.opted_out and not p.locked_by_admin
                """,
                        (row, index) ->
                                new Submission(
                                        UUID.fromString(row.getString(1)),
                                        row.getString(2),
                                        row.getString(3),
                                        "RETRY",
                                        row.getInt(4) + 1),
                        job,
                        scope.group(),
                        scope.epoch())
                .orElseThrow(() -> retryConflict("PROFILE_JOB_NOT_RETRYABLE", scope, job));
    }

    private static ResponseStatusException retryConflict(String code, Scope scope, UUID job) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                code,
                new IllegalStateException("groupId=" + scope.group() + ", jobId=" + job));
    }

    public String self(UUID group, String sender, String command) {
        if (!isValidSender(sender)) {
            return "当前成员标识不支持画像功能。";
        }
        if (command == null || !SELF_COMMANDS.contains(command)) {
            return "用法：@我 /profile status|off|on|clear。";
        }
        return db.tx(() -> applySelfCommand(group, sender, command));
    }

    private String applySelfCommand(UUID group, String sender, String command) {
        Scope scope = policies.scope(group);
        Policy policy = policies.policy(group);
        Instant now = clock.instant();
        var current = stats.findMember(scope, sender);
        boolean isOptedOut = current.map(StoredMember::isOptedOut).orElse(false);
        boolean isEnabled = policies.globallyEnabled() && policy.enabled();
        if (COMMAND_STATUS.equals(command)) {
            if (isOptedOut) {
                return "你已退出本群实验性成员画像。";
            }
            return isEnabled ? "你未退出本群实验性成员画像；满足活跃条件后参与。" : NOT_ENABLED;
        }
        // 关闭建模不阻止已有成员删除自己的数据。
        if (!isEnabled && current.isEmpty()) {
            return NOT_ENABLED;
        }

        // SIDE EFFECT: 保存参与设置与清除水位；首次退出也必须持久化。
        UUID id = stats.ensure(scope, sender, MemberProfileActivity.ANONYMOUS_MEMBER, now);
        String suffix;
        String response;
        switch (command) {
            case COMMAND_OFF -> {
                MemberProfileLifecycle.clearMember(db, scope, id, sender, true, now);
                suffix = "OPT_OUT";
                response = "已退出并清除你在本群的成员画像。";
            }
            case COMMAND_CLEAR -> {
                MemberProfileLifecycle.clearMember(db, scope, id, sender, isOptedOut, now);
                suffix = "CLEAR";
                response = "已清除你在本群的成员画像，参与设置不变。";
            }
            default -> {
                if (isOptedOut) {
                    MemberProfileLifecycle.clearMember(db, scope, id, sender, false, now);
                }
                suffix = "OPT_IN";
                response = "已允许重新建立你在本群的成员画像。";
            }
        }
        db.audit("MEMBER_PROFILE_SELF_" + suffix, group, Map.of("profileId", id));
        return response;
    }

    Optional<UUID> queue(Scope scope, Submission submission, Instant now) {
        UUID id = UUID.randomUUID();
        // SIDE EFFECT: 唯一约束拒绝同一成员已有的待执行作业。
        int inserted =
                db.exec(
                        """
                insert into member_profile_job(
                    id,group_id,epoch,profile_id,sender_id,job_type,trigger_type,state,attempt,created_at)
                values(?,?,?,?,?,?,?,'QUEUED',?,?) on conflict do nothing
                """,
                        id,
                        scope.group(),
                        scope.epoch(),
                        submission.profileId(),
                        submission.sender(),
                        submission.type(),
                        submission.trigger(),
                        submission.attempt(),
                        now);
        return inserted == 1 ? Optional.of(id) : Optional.empty();
    }

    private Policy requireAvailablePolicy(Scope scope, Instant now) {
        Policy policy = policies.policy(scope.group());
        if (!policies.globallyEnabled() || !scope.collecting() || !policy.enabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PROFILE_DISABLED");
        }
        if (policies.remaining(scope.group(), policy, now) == 0) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "PROFILE_DAILY_BUDGET_EXHAUSTED");
        }
        return policy;
    }

    private StoredMember requireMember(Scope scope, String sender) {
        return stats.findMember(scope, sender)
                .orElseThrow(() -> missing("groupId=" + scope.group() + ", senderId=" + sender));
    }

    private void cancelJobs(UUID profile, Instant now, String code) {
        db.exec(
                "update member_profile_job set state='CANCELLED',error_code=?,finished_at=? where profile_id=? and state in ('QUEUED','RUNNING')",
                code,
                now,
                profile);
    }

    private static void validateSender(String sender) {
        if (!isValidSender(sender)) {
            throw bad("INVALID_PROFILE_SENDER");
        }
    }

    private static boolean isValidSender(String sender) {
        return sender != null && !sender.isBlank() && sender.length() <= MAX_SENDER_LENGTH;
    }

    private static ResponseStatusException missing(String context) {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", new IllegalStateException(context));
    }

    private static ResponseStatusException bad(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    record Submission(UUID profileId, String sender, String type, String trigger, int attempt) {}
}
