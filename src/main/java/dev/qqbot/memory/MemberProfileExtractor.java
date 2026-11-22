/**
 * [INPUT]: 依赖作业范围、本人证据、观测存储及计量生成端口。
 * [OUTPUT]: 从证据补丁更新观测，或从确认观测重铸卡片。
 * [POS]: 画像作业编排。模型在事务外运行，发布事务复验作业版本和来源。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.MemoryProperties;
import dev.qqbot.RetentionProperties;
import dev.qqbot.memory.MemberProfileEvidence.Batch;
import dev.qqbot.memory.MemberProfileJobs.Snapshot;
import dev.qqbot.memory.MemberProfileObservations.Observation;
import dev.qqbot.memory.MemberProfilePolicyService.Policy;
import dev.qqbot.memory.MemberProfilePolicyService.Scope;
import dev.qqbot.memory.MemberProfilePrompts.ExtractPrompt;
import dev.qqbot.model.GenerationObserver;
import dev.qqbot.model.GenerationRequest;
import dev.qqbot.model.Purpose;
import dev.qqbot.model.TextGenerator;
import dev.qqbot.persistence.Db;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Service;

@Service
public class MemberProfileExtractor {
    private static final int GENERATION_TIMEOUT_SECONDS = 90;
    private static final int REFLECTION_CHANGE_THRESHOLD = 3;
    private static final Duration REFLECTION_WINDOW = Duration.ofDays(7);
    private static final int CARD_STATEMENT_LIMIT = 80;
    private final Db db;
    private final Clock clock;
    private final MemoryProperties budgets;
    private final ProfileGuardrails guard;
    private final TextGenerator generator;
    private final MemberProfilePolicyService policies;
    private final MemberProfileEvidence evidence;
    private final MemberProfileObservations observations;
    private final MemberProfilePrompts prompts;

    /** 连接画像抽取依赖。生成器接受已检查预算的单次请求。 */
    public MemberProfileExtractor(
            Db db,
            Clock clock,
            RetentionProperties retention,
            MemoryProperties budgets,
            ProfileGuardrails guard,
            TextGenerator generator,
            MemberProfilePolicyService policies) {
        this.db = db;
        this.clock = clock;
        this.budgets = budgets;
        this.guard = guard;
        this.generator = generator;
        this.policies = policies;
        this.evidence = new MemberProfileEvidence(db, retention);
        this.observations = new MemberProfileObservations(db);
        this.prompts = new MemberProfilePrompts(db, budgets.inputTokens());
    }

    enum JobType {
        EXTRACT,
        REPLAY,
        REFLECT
    }

    record Job(UUID id, JobType type, UUID profile, String sender) {}

    record Cycle(Scope scope, Policy policy, Instant now) {}

    record Work(Job job, Snapshot member, Cycle cycle) {
        UUID id() {
            return job.id();
        }

        UUID profile() {
            return job.profile();
        }

        String sender() {
            return job.sender();
        }

        long version() {
            return member.identity().version();
        }

        Scope scope() {
            return cycle.scope();
        }

        Policy policy() {
            return cycle.policy();
        }

        Instant now() {
            return cycle.now();
        }
    }

    boolean due(Snapshot member, Cycle cycle) {
        return evidence.hasUpdates(cycle, member);
    }

    static boolean reflectionDue(Snapshot member, Instant now) {
        if (member.progress().replayThrough() != null) {
            return false;
        }
        if (member.reflection().needsReflection()) {
            return true;
        }
        long changes = member.reflection().progress().changes();
        Instant reflected = member.reflection().progress().lastReflectedAt();
        return changes >= REFLECTION_CHANGE_THRESHOLD
                || (changes > 0
                        && (reflected == null || !reflected.plus(REFLECTION_WINDOW).isAfter(now)));
    }

    void execute(Work work) {
        if (work.job().type() == JobType.REFLECT) {
            reflect(work);
        } else {
            extract(work);
        }
    }

    private void extract(Work work) {
        Batch batch = evidence.loadBatch(work);
        List<Observation> prior =
                batch.window().isReset()
                        ? List.of()
                        : observations.readActive(
                                work.profile(), work.now(), batch.window().isReplaying());
        ExtractPrompt prompt = prompts.renderExtract(batch, prior);
        List<ProfileGuardrails.Op> operations = requestOperations(work, prompt);
        db.tx(() -> publishExtract(work, batch, prompt, operations));
    }

    private List<ProfileGuardrails.Op> requestOperations(Work work, ExtractPrompt prompt) {
        Set<UUID> sources = prompt.supplied().keySet();
        if (sources.isEmpty()) {
            return List.of();
        }
        return guard.operations(generate(work, prompt.text()), sources, prompt.known());
    }

    private Void publishExtract(
            Work work, Batch batch, ExtractPrompt prompt, List<ProfileGuardrails.Op> operations) {
        verifyCurrent(work);
        evidence.verifySources(work, prompt.supplied().keySet(), clock.instant());
        // SIDE EFFECT: 清空、观测、水位和作业状态由同一事务提交或回滚。
        if (batch.window().isReset()) {
            resetObservations(work.profile());
        }
        int changes = 0;
        for (var operation : operations) {
            changes += observations.apply(work, operation, prompt.supplied());
        }
        advanceProfile(work, batch, prompt.cursor(), changes);
        ready(
                work,
                new Completion(
                        batch.window().range().from(),
                        prompt.cursor().through(),
                        prompt.supplied().size()));
        return null;
    }

    private void resetObservations(UUID profile) {
        db.exec("delete from member_profile_observation where profile_id=?", profile);
        db.exec(
                """
                update member_profile set profile_card='',confidence_score=0,needs_reflection=1,
                  pending_confirmed_changes=0,last_reflected_at=null where id=?
                """,
                profile);
    }

    private void advanceProfile(
            Work work, Batch batch, MemberProfilePrompts.Cursor cursor, int changes) {
        Long replayThrough =
                batch.window().isReplaying() && cursor.hasMore() ? batch.window().upper() : null;
        db.exec(
                """
                update member_profile set last_profiled_seq=?,last_profiled_at=?,replay_through_seq=?,
                  pending_confirmed_changes=pending_confirmed_changes+?,version=version+1,updated_at=? where id=?
                """,
                cursor.through(),
                work.now(),
                replayThrough,
                changes,
                clock.instant(),
                work.profile());
    }

    private void reflect(Work work) {
        List<Observation> confirmed =
                observations.readActive(work.profile(), work.now(), false).stream()
                        .filter(Observation::isConfirmed)
                        .toList();
        List<Observation> offered = prompts.selectObservations(confirmed);
        List<UUID> ids = requestReflection(work, offered);
        Card card = buildCard(offered, ids);
        db.tx(() -> publishReflection(work, card, ids.size()));
    }

    private List<UUID> requestReflection(Work work, List<Observation> offered) {
        if (offered.isEmpty()) {
            return List.of();
        }
        String prompt =
                prompts.renderReflection(
                        offered,
                        work.member().reflection().stats(),
                        work.member().reflection().note());
        Set<UUID> known = Set.copyOf(offered.stream().map(item -> item.identity().id()).toList());
        return guard.reflection(generate(work, prompt), known);
    }

    static Card buildCard(List<Observation> offered, List<UUID> ids) {
        var parts = new LinkedHashSet<String>();
        int length = 0;
        double confidence = 0;
        for (UUID id : ids) {
            Observation observation = requireObservation(offered, id);
            String statement =
                    ProfileGuardrails.cleanField(
                            observation.identity().statement(), CARD_STATEMENT_LIMIT);
            int added = statement.codePointCount(0, statement.length()) + (parts.isEmpty() ? 0 : 1);
            if (length + added <= ProfileGuardrails.MAX_CARD && parts.add(statement)) {
                length += added;
                confidence += observation.confidence();
            }
        }
        double score = parts.isEmpty() ? 0 : confidence / parts.size();
        return new Card(String.join("；", parts), score);
    }

    private static Observation requireObservation(List<Observation> offered, UUID id) {
        return offered.stream()
                .filter(value -> id.equals(value.identity().id()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Unknown profile observation: id=" + id));
    }

    private Void publishReflection(Work work, Card card, int count) {
        verifyCurrent(work);
        // SIDE EFFECT: 卡片和作业完成状态由同一事务发布。
        db.exec(
                """
                update member_profile set profile_card=?,confidence_score=?,last_reflected_at=?,needs_reflection=0,
                  pending_confirmed_changes=0,version=version+1,updated_at=? where id=?
                """,
                card.text(),
                card.score(),
                work.now(),
                clock.instant(),
                work.profile());
        ready(work, new Completion(null, null, count));
        return null;
    }

    private String generate(Work work, String prompt) {
        if (!prompts.fitsInput(prompt)) {
            throw new IllegalStateException(MemberProfilePrompts.INPUT_TOO_LARGE);
        }
        verifyCurrent(work);
        UUID group = work.scope().group();
        if (policies.remaining(group, policies.policy(group), clock.instant()) == 0) {
            throw new IllegalStateException("PROFILE_DAILY_BUDGET_EXHAUSTED");
        }
        // SIDE EFFECT: 生成器记录模型调用与用量。
        return generator
                .generate(generationRequest(work, prompt), GenerationObserver.noop())
                .text();
    }

    private GenerationRequest generationRequest(Work work, String prompt) {
        return new GenerationRequest(
                work.id(),
                UUID.randomUUID(),
                work.scope().group(),
                work.scope().epoch(),
                Purpose.MEMBER_PROFILE,
                MemberProfilePrompts.SYSTEM,
                prompt,
                budgets.inputTokens(),
                budgets.profileOutputTokens(),
                GENERATION_TIMEOUT_SECONDS);
    }

    private void verifyCurrent(Work work) {
        boolean isCurrent =
                db.find(
                                """
                select j.id from member_profile_job j join member_profile p on p.id=j.profile_id
                  join group_config g on g.id=j.group_id and g.epoch=j.epoch
                  join member_profile_policy q on q.group_id=g.id
                where j.id=? and j.state='RUNNING' and p.version=? and p.active_slot
                  and not p.opted_out and not p.locked_by_admin and g.enabled and q.enabled
                """,
                                (row, number) -> true,
                                work.id(),
                                work.version())
                        .isPresent();
        if (!isCurrent) {
            throw new CancellationException("PROFILE_SCOPE_CHANGED");
        }
    }

    private void ready(Work work, Completion result) {
        db.exec(
                """
                update member_profile_job set state='READY',from_seq=?,to_seq=?,source_count=?,finished_at=?,error_code=null
                where id=? and state='RUNNING'
                """,
                result.from(),
                result.through(),
                result.sourceCount(),
                clock.instant(),
                work.id());
    }

    record Card(String text, double score) {}

    private record Completion(Long from, Long through, int sourceCount) {}
}
