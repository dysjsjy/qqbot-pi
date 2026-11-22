/**
 * [INPUT]: 接收经过模型护栏检查的操作、本人证据与画像作业范围。
 * [OUTPUT]: 计算并写入观测状态、证据窗口、有效期和替换关系。
 * [POS]: 画像观测持久化边界；调用方持有覆盖整批的 SQLite 事务。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static dev.qqbot.memory.MemberProfilePolicyService.ZONE;

import dev.qqbot.memory.MemberProfileEvidence.Source;
import dev.qqbot.memory.MemberProfileExtractor.Work;
import dev.qqbot.memory.ProfileGuardrails.Action;
import dev.qqbot.memory.ProfileGuardrails.Category;
import dev.qqbot.memory.ProfileGuardrails.Op;
import dev.qqbot.persistence.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;

final class MemberProfileObservations {
    static final String CONFIRMED = "CONFIRMED";
    private static final String CANDIDATE = "CANDIDATE";
    private static final int MAX_SOURCE_VALUES = 64;
    private static final long SECONDS_PER_WINDOW = 3_600;
    private static final int CANDIDATE_TTL_DAYS = 14;
    private static final int MIN_CONFIRMING_EVIDENCE = 2;
    private static final int MIN_DISTINCT_DAYS = 2;
    private static final int MIN_DISTINCT_WINDOWS = 3;
    private static final String UPSERT =
            """
            insert into member_profile_observation(id,profile_id,group_id,epoch,category,statement,confidence,
                evidence_count,distinct_days,state,supersedes_id,source_message_ids_json,source_days_json,
                source_windows_json,first_seen_at,last_reinforced_at,expires_at,created_at,updated_at)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            on conflict(id) do update set confidence=excluded.confidence,evidence_count=excluded.evidence_count,
                distinct_days=excluded.distinct_days,state=excluded.state,
                source_message_ids_json=excluded.source_message_ids_json,source_days_json=excluded.source_days_json,
                source_windows_json=excluded.source_windows_json,first_seen_at=excluded.first_seen_at,
                last_reinforced_at=excluded.last_reinforced_at,expires_at=excluded.expires_at,updated_at=excluded.updated_at
            """;
    private final Db db;

    MemberProfileObservations(Db db) {
        this.db = db;
    }

    List<Observation> readActive(UUID profile, Instant now, boolean isReplaying) {
        return db.query(
                """
                select * from member_profile_observation where profile_id=? and state in ('CONFIRMED','CANDIDATE')
                  and (? or expires_at>?) order by state desc,last_reinforced_at desc,id limit ?
                """,
                this::readObservation,
                profile,
                isReplaying,
                now,
                ProfileGuardrails.MAX_OBSERVATIONS);
    }

    int apply(Work work, Op operation, Map<UUID, Source> sources) {
        Observation target = findTarget(work.profile(), operation).orElse(null);
        validateReinforcement(operation, target);
        Observation match = findMatch(work.profile(), operation, target).orElse(null);
        if (match == null && isAtCapacity(work.profile())) {
            return 0;
        }
        List<Source> cited = citedSources(operation, sources);
        Optional<Update> update =
                planUpdate(work.policy().profileTtlDays(), operation, match, cited);
        return update.map(change -> publish(work, operation, change)).orElse(0);
    }

    private static List<Source> citedSources(Op operation, Map<UUID, Source> sources) {
        var cited = new ArrayList<Source>();
        for (UUID id : operation.sourceIds()) {
            Source source = sources.get(id);
            if (source == null) {
                throw new CancellationException("PROFILE_SOURCES_CHANGED");
            }
            cited.add(source);
        }
        return List.copyOf(cited);
    }

    private Optional<Observation> findTarget(UUID profile, Op operation) {
        if (operation.targetId() == null) {
            return Optional.empty();
        }
        var target =
                db.find(
                        """
                select * from member_profile_observation where id=? and profile_id=?
                  and state in ('CONFIRMED','CANDIDATE')
                """,
                        this::readObservation,
                        operation.targetId(),
                        profile);
        if (target.isEmpty()) {
            throw new CancellationException("PROFILE_OBSERVATION_CHANGED");
        }
        return target;
    }

    private static void validateReinforcement(Op operation, Observation target) {
        if (operation.action() == Action.REINFORCE
                && (target == null
                        || !operation.statement().equals(target.identity().statement())
                        || !operation.category().name().equals(target.identity().category()))) {
            throw new IllegalArgumentException("INVALID_PROFILE_REINFORCEMENT");
        }
    }

    private Optional<Observation> findMatch(UUID profile, Op operation, Observation target) {
        if (target != null && !isReplacement(operation)) {
            return Optional.of(target);
        }
        return db.find(
                """
                select * from member_profile_observation where profile_id=? and category=? and statement=?
                  and state in ('CANDIDATE','CONFIRMED') and supersedes_id is ?
                order by last_reinforced_at desc limit 1
                """,
                this::readObservation,
                profile,
                operation.category().name(),
                operation.statement(),
                isReplacement(operation) ? operation.targetId() : null);
    }

    private boolean isAtCapacity(UUID profile) {
        long count =
                db.find(
                                """
                select count(*) from member_profile_observation where profile_id=? and state in ('CANDIDATE','CONFIRMED')
                """,
                                (row, number) -> row.getLong(1),
                                profile)
                        .orElseThrow();
        return count >= ProfileGuardrails.MAX_OBSERVATIONS;
    }

    static Optional<Update> planUpdate(
            int profileTtlDays, Op operation, Observation match, List<Source> cited) {
        Evidence initial = match == null ? Evidence.empty() : match.evidence();
        Evidence evidence = mergeEvidence(initial, cited);
        if (evidence.count() == initial.count()) {
            return Optional.empty();
        }
        boolean wasConfirmed = match != null && match.isConfirmed();
        boolean isConfirmed = wasConfirmed || hasConfirmingEvidence(evidence);
        int ttl =
                isConfirmed
                        ? confirmedTtl(operation.category(), profileTtlDays)
                        : CANDIDATE_TTL_DAYS;
        double confidence =
                match == null
                        ? operation.confidence()
                        : Math.max(operation.confidence(), match.confidence());
        UUID replaces =
                isReplacement(operation)
                        ? operation.targetId()
                        : match == null ? null : match.supersedes();
        var state =
                new Revision(
                        isConfirmed ? CONFIRMED : CANDIDATE, confidence, replaces, wasConfirmed);
        Instant expiresAt = evidence.times().latest().plus(Duration.ofDays(ttl));
        UUID observationId = match == null ? null : match.identity().id();
        return Optional.of(new Update(observationId, evidence, state, expiresAt));
    }

    static Evidence mergeEvidence(Evidence initial, List<Source> cited) {
        var ids = new LinkedHashSet<>(initial.values().ids());
        var days = new LinkedHashSet<>(initial.values().days());
        var windows = new LinkedHashSet<>(initial.values().windows());
        Instant first = initial.times().first();
        Instant latest = initial.times().latest();
        long count = initial.count();
        for (Source source : cited) {
            if (!ids.add(source.id().toString())) {
                continue;
            }
            count++;
            Instant occurred = source.when();
            days.add(occurred.atZone(ZONE).toLocalDate().toString());
            windows.add(Long.toString(occurred.getEpochSecond() / SECONDS_PER_WINDOW));
            first = occurred.isBefore(first) ? occurred : first;
            latest = occurred.isAfter(latest) ? occurred : latest;
        }
        return new Evidence(new Values(ids, days, windows), new Times(first, latest), count);
    }

    private static boolean hasConfirmingEvidence(Evidence evidence) {
        Values values = evidence.values();
        return evidence.count() >= MIN_CONFIRMING_EVIDENCE
                && (values.days().size() >= MIN_DISTINCT_DAYS
                        || values.windows().size() >= MIN_DISTINCT_WINDOWS);
    }

    private int publish(Work work, Op operation, Update update) {
        // SIDE EFFECT: 观测、替换和卡片失效依赖调用方事务共同提交。
        saveObservation(work, operation, update);
        if (update.revision().isConfirmed()
                && markSuperseded(work, update.revision().supersedes())) {
            return 1;
        }
        return update.revision().isConfirmed() && !update.revision().wasConfirmed() ? 1 : 0;
    }

    private void saveObservation(Work work, Op operation, Update update) {
        Evidence evidence = update.evidence();
        Values values = evidence.values();
        Revision revision = update.revision();
        UUID id = update.id() == null ? UUID.randomUUID() : update.id();
        db.exec(
                UPSERT,
                id,
                work.profile(),
                work.scope().group(),
                work.scope().epoch(),
                operation.category().name(),
                operation.statement(),
                revision.confidence(),
                evidence.count(),
                Math.min(values.days().size(), MAX_SOURCE_VALUES),
                revision.state(),
                revision.supersedes(),
                db.json(tail(values.ids())),
                db.json(tail(values.days())),
                db.json(tail(values.windows())),
                evidence.times().first(),
                evidence.times().latest(),
                update.expiresAt(),
                work.now(),
                work.now());
    }

    private boolean markSuperseded(Work work, UUID replaced) {
        if (replaced == null
                || db.exec(
                                """
                update member_profile_observation set state='SUPERSEDED',updated_at=?
                where id=? and profile_id=? and state in ('CANDIDATE','CONFIRMED')
                """,
                                work.now(),
                                replaced,
                                work.profile())
                        == 0) {
            return false;
        }
        db.exec(
                "update member_profile set profile_card='',needs_reflection=1 where id=?",
                work.profile());
        return true;
    }

    private Observation readObservation(ResultSet row, int rowNumber) throws SQLException {
        var identity =
                new Identity(
                        UUID.fromString(row.getString("id")),
                        row.getString("category"),
                        row.getString("statement"),
                        row.getString("state"));
        String supersedes = row.getString("supersedes_id");
        Evidence evidence = readEvidence(row);
        return new Observation(
                identity,
                evidence,
                row.getDouble("confidence"),
                supersedes == null ? null : UUID.fromString(supersedes));
    }

    private Evidence readEvidence(ResultSet row) throws SQLException {
        var values =
                new Values(
                        parseValues(row.getString("source_message_ids_json")),
                        parseValues(row.getString("source_days_json")),
                        parseValues(row.getString("source_windows_json")));
        var times =
                new Times(
                        Instant.ofEpochMilli(row.getLong("first_seen_at")),
                        Instant.ofEpochMilli(row.getLong("last_reinforced_at")));
        return new Evidence(values, times, row.getLong("evidence_count"));
    }

    private List<String> parseValues(String json) {
        List<?> stored = db.parse(json, List.class);
        var unique = new LinkedHashSet<String>();
        for (Object value : stored) {
            unique.add(value.toString());
        }
        return List.copyOf(unique);
    }

    private static List<String> tail(List<String> values) {
        return values.subList(Math.max(0, values.size() - MAX_SOURCE_VALUES), values.size());
    }

    private static boolean isReplacement(Op operation) {
        return operation.action() == Action.UPDATE || operation.action() == Action.CONTRADICT;
    }

    private static int confirmedTtl(Category category, int base) {
        return switch (category) {
            case RUNNING_GAG -> Math.max(7, base / 2);
            case INTEREST_TOPIC -> Math.max(10, base * 3 / 4);
            default -> base;
        };
    }

    record Identity(UUID id, String category, String statement, String state) {}

    record Observation(Identity identity, Evidence evidence, double confidence, UUID supersedes) {
        boolean isConfirmed() {
            return CONFIRMED.equals(identity.state());
        }
    }

    record Values(List<String> ids, List<String> days, List<String> windows) {
        Values(Collection<String> ids, Collection<String> days, Collection<String> windows) {
            this(List.copyOf(ids), List.copyOf(days), List.copyOf(windows));
        }
    }

    record Times(Instant first, Instant latest) {}

    record Evidence(Values values, Times times, long count) {
        static Evidence empty() {
            return new Evidence(
                    new Values(List.of(), List.of(), List.of()),
                    new Times(Instant.MAX, Instant.MIN),
                    0);
        }
    }

    record Revision(String state, double confidence, UUID supersedes, boolean wasConfirmed) {
        boolean isConfirmed() {
            return CONFIRMED.equals(state);
        }
    }

    record Update(UUID id, Evidence evidence, Revision revision, Instant expiresAt) {}
}
