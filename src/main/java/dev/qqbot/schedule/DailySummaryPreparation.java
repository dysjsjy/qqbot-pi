/**
 * [INPUT]: 依赖 SQLite 日报队列、群代际、冻结来源和采集状态。
 * [OUTPUT]: 在认领事务冻结原文、昵称与采集覆盖，或结束失效、超限和无数据作业。
 * [POS]: 日报认领事务，保存快照后才交给 Worker 发起模型调用。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static dev.qqbot.schedule.DailySummaryService.ZONE;

import dev.qqbot.persistence.Db;
import dev.qqbot.schedule.DailySummaryCoverage.Capture;
import dev.qqbot.schedule.DailySummaryCoverage.Counts;
import dev.qqbot.schedule.DailySummaryCoverage.Gap;
import dev.qqbot.schedule.DailySummaryCoverage.Window;
import dev.qqbot.schedule.DailySummaryFacts.SourceMessage;
import dev.qqbot.schedule.DailySummaryService.Snapshot;
import dev.qqbot.schedule.DailySummaryService.Snapshot.Header;
import dev.qqbot.schedule.DailySummaryService.Snapshot.Material;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class DailySummaryPreparation {
    private static final int MAX_MESSAGES = 1_000;
    private static final int MAX_TEXT_CODE_POINTS = 100_000;
    private final Db db;
    private final Clock clock;
    private final boolean includesOfficialQqNotice;

    DailySummaryPreparation(Db db, Clock clock, boolean includesOfficialQqNotice) {
        this.db = db;
        this.clock = clock;
        this.includesOfficialQqNotice = includesOfficialQqNotice;
    }

    Optional<Snapshot> claim(UUID id) {
        return db.tx(() -> claimInTransaction(id));
    }

    private Optional<Snapshot> claimInTransaction(UUID id) {
        return findQueued(id).flatMap(this::claimQueued);
    }

    private Optional<Queued> findQueued(UUID id) {
        return db.find(
                "select id,group_id,epoch,business_date,snapshot_at,coverage from daily_run where id=? and state='QUEUED'",
                DailySummaryPreparation::readQueued,
                id);
    }

    private static Queued readQueued(ResultSet row, int rowNumber) throws SQLException {
        Header header =
                new Header(
                        UUID.fromString(row.getString("id")),
                        UUID.fromString(row.getString("group_id")),
                        row.getLong("epoch"),
                        LocalDate.parse(row.getString("business_date")));
        return new Queued(header, readInstant(row, "snapshot_at"), row.getString("coverage"));
    }

    private Optional<Snapshot> claimQueued(Queued queued) {
        Header header = queued.header();
        var group = findGroup(header.group()).filter(value -> value.isCurrent(header.epoch()));
        if (group.isEmpty()) {
            cancel(header.id());
            return Optional.empty();
        }
        Loaded loaded = load(queued, group.orElseThrow());
        var snapshot = new Snapshot(header, material(header, loaded.messages()), loaded.coverage());
        return publishClaim(queued, loaded, snapshot);
    }

    private Loaded load(Queued queued, Group group) {
        Header header = queued.header();
        Instant now = clock.instant();
        Window window = businessWindow(header.date(), now);
        Instant enabledSince = group.enabledSince(window.from());
        List<Gap> gaps = readGaps(header.group(), window, enabledSince);
        SummaryProfile profile = findProfile(header.group()).orElse(null);
        if (queued.snapshotAt() != null) {
            return reopen(queued, group, profile, new WindowState(window, enabledSince, gaps));
        }
        return freeze(header, group, profile, new WindowState(window, enabledSince, gaps));
    }

    static Window businessWindow(LocalDate date, Instant snapshotAt) {
        Instant from = date.atStartOfDay(ZONE).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZONE).toInstant();
        return new Window(from, to, snapshotAt);
    }

    private Loaded freeze(Header header, Group group, SummaryProfile profile, WindowState state) {
        Window window = new Window(state.window().from(), state.window().to(), clock.instant());
        List<SourceMessage> messages = readNewSources(header, window);
        long nonText = countNonText(header, window);
        var capture = new Capture(window, new Counts(messages.size(), nonText), state.gaps());
        SummaryProfile effective = profile == null ? SummaryProfile.defaults() : profile;
        var coverage =
                DailySummaryCoverage.freeze(
                        db, capture, effective, includesOfficialQqNotice && group.isQq());
        return new Loaded(messages, coverage, window.snapshotAt(), !state.gaps().isEmpty());
    }

    private Loaded reopen(Queued queued, Group group, SummaryProfile profile, WindowState state) {
        List<SourceMessage> messages = readFrozenSources(queued.header());
        boolean removesMissingCollection =
                group.collectionSince() == null
                        && !state.enabledSince().isAfter(state.window().from());
        var rules =
                new DailySummaryCoverage.Reopen(
                        removesMissingCollection, includesOfficialQqNotice && group.isQq());
        var coverage = DailySummaryCoverage.reopen(db, queued.coverageJson(), profile, rules);
        if (messages.size() != coverage.messages()) {
            throw new IllegalStateException("SOURCE_SNAPSHOT_EXPIRED");
        }
        return new Loaded(messages, coverage, queued.snapshotAt(), !state.gaps().isEmpty());
    }

    private Optional<Snapshot> publishClaim(Queued job, Loaded loaded, Snapshot snapshot) {
        UUID id = job.header().id();
        boolean isOversized = exceedsLimit(loaded.messages());
        // SIDE EFFECT: 冻结水位和作业状态在同一 SQLite 事务内提交。
        if (job.snapshotAt() == null) {
            saveSnapshot(loaded, snapshot);
        }
        if (isOversized) {
            markOversized(id);
            return Optional.empty();
        }
        if (loaded.messages().isEmpty()) {
            String report = loaded.hasCurrentGaps() ? "昨日无已采集文本，且采集不完整。" : "昨日无可分析文本。";
            markEmpty(id, report);
            return Optional.empty();
        }
        int claimed =
                db.exec("update daily_run set state='RUNNING' where id=? and state='QUEUED'", id);
        return claimed == 1 ? Optional.of(snapshot) : Optional.empty();
    }

    private void saveSnapshot(Loaded loaded, Snapshot snapshot) {
        db.exec(
                "update daily_run set snapshot_at=?,source_ids=?,coverage=? where id=?",
                loaded.snapshotAt(),
                db.json(snapshot.sourceIds()),
                loaded.coverage().json(),
                snapshot.header().id());
    }

    private void cancel(UUID id) {
        db.exec(
                """
                update daily_run set state='CANCELLED',finished_at=cast(unixepoch('subsec')*1000 as integer)
                where id=?
                """,
                id);
    }

    private void markOversized(UUID id) {
        db.exec(
                """
                update daily_run set state='LIMIT_EXCEEDED',error_code='INPUT_LIMIT',
                  finished_at=cast(unixepoch('subsec')*1000 as integer) where id=?
                """,
                id);
    }

    private void markEmpty(UUID id, String report) {
        db.exec(
                """
                update daily_run set state='NO_DATA',report=?,finished_at=cast(unixepoch('subsec')*1000 as integer)
                where id=?
                """,
                report,
                id);
    }

    private Optional<Group> findGroup(UUID id) {
        return db.find(
                "select epoch,enabled,channel,collection_since,created_at from group_config where id=?",
                DailySummaryPreparation::readGroup,
                id);
    }

    private static Group readGroup(ResultSet row, int rowNumber) throws SQLException {
        return new Group(
                row.getLong("epoch"),
                row.getBoolean("enabled"),
                "QQ".equals(row.getString("channel")),
                new CollectionTimes(
                        readInstant(row, "collection_since"), readInstant(row, "created_at")));
    }

    private Optional<SummaryProfile> findProfile(UUID group) {
        return db.find(
                "select style_mode,speaker_mode from daily_schedule where group_id=?",
                DailySummaryPreparation::readProfile,
                group);
    }

    private static SummaryProfile readProfile(ResultSet row, int rowNumber) throws SQLException {
        return SummaryProfile.of(row.getString("style_mode"), row.getString("speaker_mode"));
    }

    private List<Gap> readGaps(UUID group, Window window, Instant enabledSince) {
        var gaps =
                new ArrayList<>(
                        db.query(
                                """
                select starts_at,ends_at,reason from collection_gap where group_id=? and starts_at<?
                  and coalesce(ends_at,?)>? order by starts_at
                """,
                                DailySummaryPreparation::readGap,
                                group,
                                window.to(),
                                window.snapshotAt(),
                                window.from()));
        if (enabledSince.isAfter(window.from())) {
            Instant end = enabledSince.isBefore(window.to()) ? enabledSince : window.to();
            gaps.add(
                    new Gap(window.from(), end, DailySummaryCoverage.COLLECTION_NOT_ENABLED, true));
        }
        return List.copyOf(gaps);
    }

    private static Gap readGap(ResultSet row, int rowNumber) throws SQLException {
        return new Gap(
                readInstant(row, "starts_at"),
                readInstant(row, "ends_at"),
                row.getString("reason"),
                false);
    }

    private static Instant readInstant(ResultSet row, String key) throws SQLException {
        long ms = row.getLong(key);
        return row.wasNull() ? null : Instant.ofEpochMilli(ms);
    }

    private Material material(Header header, List<SourceMessage> messages) {
        if (messages.isEmpty() || exceedsLimit(messages)) {
            return new Material(messages, Map.of());
        }
        return new Material(messages, readNames(header));
    }

    static boolean exceedsLimit(List<SourceMessage> messages) {
        return messages.size() > MAX_MESSAGES
                || messages.stream()
                                .map(SourceMessage::text)
                                .mapToLong(text -> text.codePointCount(0, text.length()))
                                .sum()
                        > MAX_TEXT_CODE_POINTS;
    }

    private record Queued(Header header, Instant snapshotAt, String coverageJson) {}

    private record CollectionTimes(Instant since, Instant created) {}

    private record Group(long epoch, boolean isEnabled, boolean isQq, CollectionTimes collection) {
        Instant collectionSince() {
            return collection.since();
        }

        boolean isCurrent(long expectedEpoch) {
            return isEnabled && epoch == expectedEpoch;
        }

        Instant enabledSince(Instant fallback) {
            if (collection.since() != null) {
                return collection.since();
            }
            return collection.created() == null ? fallback : collection.created();
        }
    }

    private record WindowState(Window window, Instant enabledSince, List<Gap> gaps) {}

    private record Loaded(
            List<SourceMessage> messages,
            DailySummaryCoverage coverage,
            Instant snapshotAt,
            boolean hasCurrentGaps) {}

    private List<SourceMessage> readNewSources(Header header, Window window) {
        return db.query(
                """
                select id,sender_name,body,occurred_at from group_message where group_id=? and epoch=? and kind='TEXT'
                  and occurred_at>=? and occurred_at<? and received_at<=? and body not like '/%' order by occurred_at,id
                """,
                DailySummaryPreparation::readMessage,
                header.group(),
                header.epoch(),
                window.from(),
                window.to(),
                window.snapshotAt());
    }

    private List<SourceMessage> readFrozenSources(Header header) {
        return db.query(
                """
                select m.id,m.sender_name,m.body,m.occurred_at from group_message m join daily_run r on r.id=?
                where m.group_id=? and m.epoch=? and m.id in (select value from json_each(r.source_ids))
                order by m.occurred_at,m.id
                """,
                DailySummaryPreparation::readMessage,
                header.id(),
                header.group(),
                header.epoch());
    }

    private static SourceMessage readMessage(ResultSet row, int rowNumber) throws SQLException {
        return new SourceMessage(
                row.getString("id"), new Timestamp(row.getLong("occurred_at")).toString(),
                row.getString("sender_name"), row.getString("body"));
    }

    private long countNonText(Header header, Window window) {
        return db.find(
                        """
                select count(*) from group_message where group_id=? and epoch=? and kind='NON_TEXT'
                  and occurred_at>=? and occurred_at<? and received_at<=?
                """,
                        (row, number) -> row.getLong(1),
                        header.group(),
                        header.epoch(),
                        window.from(),
                        window.to(),
                        window.snapshotAt())
                .orElseThrow();
    }

    private Map<String, String> readNames(Header header) {
        var rows =
                db.query(
                        """
                select sender_id,sender_name from group_message where group_id=? and epoch=?
                  and sender_id<>'' and sender_name<>'' order by occurred_at
                """,
                        (row, number) -> new MemberName(row.getString(1), row.getString(2)),
                        header.group(),
                        header.epoch());
        var names = new LinkedHashMap<String, String>();
        for (MemberName member : rows) {
            names.put(member.id(), member.name());
        }
        return Map.copyOf(names);
    }

    private record MemberName(String id, String name) {}
}
