/**
 * [INPUT]: 依赖画像策略、活跃窗口计算和当前群 SQLite 行。
 * [OUTPUT]: 刷新活跃席位与互动边，读取有类型的成员状态。
 * [POS]: 在调用方事务内执行 L0 刷新，保留锁定席位和重放优先级。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static dev.qqbot.memory.MemberProfileActivity.ACTIVITY;
import static dev.qqbot.memory.MemberProfileActivity.display;

import dev.qqbot.memory.MemberProfileActivity.Candidate;
import dev.qqbot.memory.MemberProfileActivity.Snapshot;
import dev.qqbot.memory.MemberProfileActivity.Window;
import dev.qqbot.memory.MemberProfilePolicyService.Policy;
import dev.qqbot.memory.MemberProfilePolicyService.Scope;
import dev.qqbot.persistence.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MemberProfileStats {
    private static final int MAX_SENDER_LENGTH = 160;
    private final Db db;
    private final MemberProfileActivity activity;
    private final MemberProfileEdges edges;

    public MemberProfileStats(Db db) {
        this.db = db;
        this.activity = new MemberProfileActivity(db);
        this.edges = new MemberProfileEdges(db);
    }

    List<UUID> refresh(Scope scope, Policy policy, Instant now) {
        return refresh(scope, policy, new RefreshRequest(now, false, null));
    }

    List<UUID> refresh(Scope scope, Policy policy, RefreshRequest request) {
        var window = Window.endingAt(request.now(), policy.coldStartLookbackDays());
        var cycle = new Cycle(scope, policy, request, window);
        var reserved = new LinkedHashSet<>(loadLocked(scope, policy.maxProfiles()));
        var selection = selectCandidates(cycle);
        var floors = edges.sourceFloors(scope);
        return publish(cycle, selection, reserved, floors);
    }

    private Selection selectCandidates(Cycle cycle) {
        var eligible = new ArrayList<Snapshot>();
        var previews = new ArrayList<Snapshot>();
        var replaying = new HashSet<>(loadReplaying(cycle.scope()));
        for (String sender : loadSenders(cycle)) {
            var candidate = findCandidate(cycle, sender, replaying);
            if (candidate.isPresent()) {
                addCandidate(cycle, eligible, previews, candidate.orElseThrow());
            }
        }
        eligible.sort(priority(replaying));
        return new Selection(List.copyOf(eligible), List.copyOf(previews));
    }

    private Optional<Candidate> findCandidate(Cycle cycle, String sender, Set<String> replaying) {
        if (sender.length() > MAX_SENDER_LENGTH) {
            return Optional.empty();
        }
        var profile =
                db.find(
                        """
                select locked_by_admin,last_profiled_at,replay_through_seq,needs_reflection,source_floor_seq
                from member_profile where group_id=? and epoch=? and sender_id=?
                """,
                        (row, index) -> readCandidateState(row),
                        cycle.scope().group(),
                        cycle.scope().epoch(),
                        sender);
        var state = profile.orElse(CandidateState.NEW);
        if (state.isLocked()) {
            return Optional.empty();
        }
        if (cycle.request().includesReplay(sender)) {
            replaying.add(sender);
        }
        return Optional.of(
                new Candidate(
                        sender,
                        state.sourceFloor(),
                        state.hasColdStart() || replaying.contains(sender)));
    }

    private static CandidateState readCandidateState(ResultSet row) throws SQLException {
        boolean hasColdStart =
                row.getObject("last_profiled_at") == null
                        || row.getObject("replay_through_seq") != null
                        || row.getBoolean("needs_reflection");
        return new CandidateState(
                row.getBoolean("locked_by_admin"), hasColdStart, row.getLong("source_floor_seq"));
    }

    private void addCandidate(
            Cycle cycle, List<Snapshot> eligible, List<Snapshot> previews, Candidate candidate) {
        var snapshot = activity.collect(cycle.scope(), cycle.window(), candidate);
        if (snapshot.admitted() >= cycle.policy().minMessages30d()
                && snapshot.admissionDays() >= cycle.policy().minActiveDays30d()) {
            eligible.add(snapshot);
        } else if (snapshot.admitted() > 0 && previews.size() < cycle.policy().maxProfiles()) {
            previews.add(snapshot);
        }
    }

    private static Comparator<Snapshot> priority(Set<String> replaying) {
        return Comparator.<Snapshot, Boolean>comparing(item -> replaying.contains(item.sender()))
                .reversed()
                .thenComparing(Comparator.comparingInt(Snapshot::monthCount).reversed())
                .thenComparing(Comparator.comparingInt(Snapshot::admitted).reversed())
                .thenComparing(Comparator.comparing(Snapshot::lastActive).reversed())
                .thenComparing(Snapshot::sender);
    }

    private List<String> loadSenders(Cycle cycle) {
        var window = cycle.window();
        return db.query(
                "select sender_id, count(*) as n from group_message m where "
                        + ACTIVITY
                        + """
                          and not exists(
                              select 1 from member_profile p where p.group_id=m.group_id and p.epoch=m.epoch
                                and p.sender_id=m.sender_id and p.opted_out)
                          group by sender_id having count(*)>=? order by n desc,sender_id
                          """,
                (row, index) -> row.getString("sender_id"),
                cycle.scope().group(),
                cycle.scope().epoch(),
                window.earliest(),
                window.now(),
                window.now(),
                cycle.policy().minMessages30d());
    }

    private List<UUID> loadLocked(Scope scope, int limit) {
        return db.query(
                """
                select id from member_profile where group_id=? and epoch=? and locked_by_admin and not opted_out
                order by updated_at desc,id limit ?
                """,
                (row, index) -> UUID.fromString(row.getString("id")),
                scope.group(),
                scope.epoch(),
                limit);
    }

    private List<String> loadReplaying(Scope scope) {
        return db.query(
                """
                select sender_id from member_profile_job
                where group_id=? and epoch=? and job_type='REPLAY' and state in ('QUEUED','RUNNING')
                """,
                (row, index) -> row.getString("sender_id"),
                scope.group(),
                scope.epoch());
    }

    private List<UUID> publish(
            Cycle cycle, Selection selection, Set<UUID> reserved, Map<String, Long> floors) {
        // SIDE EFFECT: 调用方事务同时提交席位、预览和互动边。
        restoreLockedSlots(cycle.scope(), reserved);
        for (Snapshot snapshot : selection.eligible()) {
            if (reserved.size() >= cycle.policy().maxProfiles()) {
                break;
            }
            UUID id = save(cycle.scope(), snapshot, cycle.window().now(), true);
            edges.rebuild(cycle.scope(), cycle.window(), snapshot.sender(), floors);
            reserved.add(id);
        }
        for (Snapshot snapshot : selection.previews()) {
            save(cycle.scope(), snapshot, cycle.window().now(), false);
        }
        edges.expire(cycle.scope(), cycle.window().month());
        return List.copyOf(reserved);
    }

    private void restoreLockedSlots(Scope scope, Set<UUID> locked) {
        db.exec(
                "update member_profile set active_slot=0 where group_id=? and epoch=? and active_slot",
                scope.group(),
                scope.epoch());
        for (UUID id : locked) {
            db.exec("update member_profile set active_slot=1 where id=?", id);
        }
    }

    private UUID save(Scope scope, Snapshot snapshot, Instant now, boolean isActive) {
        UUID id = ensure(scope, snapshot.sender(), snapshot.displayName(), now);
        db.exec(
                """
                update member_profile set active_slot=?,display_name=?,aliases_json=?,l0_stats_json=?,last_active_at=?,updated_at=?
                where id=? and not opted_out and not locked_by_admin
                """,
                isActive,
                snapshot.displayName(),
                db.json(snapshot.aliases()),
                db.json(snapshot.metrics()),
                snapshot.lastActive(),
                now,
                id);
        return id;
    }

    UUID ensure(Scope scope, String sender, String name, Instant now) {
        // SIDE EFFECT: 已有成员保留身份；首次登记使用新 ID。
        db.exec(
                """
                insert into member_profile(id,group_id,epoch,sender_id,display_name,created_at,updated_at)
                values(?,?,?,?,?,?,?) on conflict do nothing
                """,
                UUID.randomUUID(),
                scope.group(),
                scope.epoch(),
                sender,
                display(name, sender),
                now,
                now);
        return findMember(scope, sender)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Member row not persisted: groupId="
                                                + scope.group()
                                                + ", senderId="
                                                + sender))
                .id();
    }

    Optional<StoredMember> findMember(Scope scope, String sender) {
        return db.find(
                """
                select id,version,opted_out,admin_note from member_profile
                where group_id=? and epoch=? and sender_id=?
                """,
                (row, index) ->
                        new StoredMember(
                                UUID.fromString(row.getString("id")),
                                row.getLong("version"),
                                row.getBoolean("opted_out"),
                                row.getString("admin_note")),
                scope.group(),
                scope.epoch(),
                sender);
    }

    static Instant time(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Timestamp stamp) {
            return stamp.toInstant();
        }
        return value == null ? null : Instant.ofEpochMilli(((Number) value).longValue());
    }

    record RefreshRequest(Instant now, boolean isReplay, String replaySender) {
        boolean includesReplay(String sender) {
            return isReplay && (replaySender == null || replaySender.equals(sender));
        }
    }

    record StoredMember(UUID id, long version, boolean isOptedOut, String adminNote) {}

    private record Cycle(Scope scope, Policy policy, RefreshRequest request, Window window) {}

    private record Selection(List<Snapshot> eligible, List<Snapshot> previews) {}

    private record CandidateState(boolean isLocked, boolean hasColdStart, long sourceFloor) {
        private static final CandidateState NEW = new CandidateState(false, true, 0L);
    }
}
