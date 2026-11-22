/**
 * [INPUT]: 接收画像作业、群范围、消息水位和冷留存策略。
 * [OUTPUT]: 按水位读取本人发言与受约束的背景前文，并在提交前复核本人来源。
 * [POS]: 画像证据的只读边界；背景不能进入可引用来源清单。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.RetentionProperties;
import dev.qqbot.memory.MemberProfileExtractor.Cycle;
import dev.qqbot.memory.MemberProfileExtractor.JobType;
import dev.qqbot.memory.MemberProfileExtractor.Work;
import dev.qqbot.memory.MemberProfileJobs.Snapshot;
import dev.qqbot.model.TokenEstimator;
import dev.qqbot.persistence.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

final class MemberProfileEvidence {
    private static final int BATCH_SIZE = 40;
    private static final int INCREMENT_DAYS = 30;
    private static final int INCREMENT_TOKENS = 1_500;
    private static final int BACKGROUND_CODE_POINTS = 100;
    private static final String FIND_BACKGROUND =
            """
            select m.body from group_message m where m.group_id=? and m.epoch=? and m.kind='TEXT'
              and m.sender_id<>? and m.event_seq<? and m.occurred_at between ? and ? and m.received_at<=?
              and not exists(select 1 from member_profile p
                where p.group_id=m.group_id and p.epoch=m.epoch and p.sender_id=m.sender_id
                and (p.opted_out or p.source_floor_seq>=m.event_seq))
            """;

    private final Db db;
    private final RetentionProperties retention;

    MemberProfileEvidence(Db db, RetentionProperties retention) {
        this.db = db;
        this.retention = retention;
    }

    boolean hasUpdates(Cycle cycle, Snapshot member) {
        Progress progress = member.progress();
        if (progress.replayThrough() != null || progress.lastProfiledAt() == null) {
            return true;
        }
        if (progress.lastProfiledAt()
                .plus(Duration.ofHours(cycle.policy().updateCooldownHours()))
                .isAfter(cycle.now())) {
            return false;
        }
        return reachesUpdateThreshold(
                readChangedMessages(cycle, member), cycle.policy().updateTriggerMessages());
    }

    private List<String> readChangedMessages(Cycle cycle, Snapshot member) {
        return db.query(
                """
                select body from group_message where group_id=? and epoch=? and sender_id=? and kind='TEXT'
                  and event_seq>? and occurred_at between ? and ? order by event_seq
                """,
                (row, number) -> ProfileGuardrails.input(row.getString("body")),
                cycle.scope().group(),
                cycle.scope().epoch(),
                member.identity().sender(),
                member.progress().lastProfiledSequence(),
                cutoff(cycle.now(), INCREMENT_DAYS, retention),
                cycle.now());
    }

    static boolean reachesUpdateThreshold(List<String> cleanedMessages, int messageLimit) {
        int messages = 0;
        int tokens = 0;
        for (String message : cleanedMessages) {
            if (message.isEmpty()) {
                continue;
            }
            messages++;
            tokens += TokenEstimator.estimateText(message);
            if (messages >= messageLimit || tokens >= INCREMENT_TOKENS) {
                return true;
            }
        }
        return false;
    }

    Batch loadBatch(Work work) {
        Window window = planWindow(work, retention);
        List<Message> rows = readSources(work, window);
        boolean hasMore = rows.size() > BATCH_SIZE;
        long through = hasMore ? rows.get(BATCH_SIZE - 1).sequence() : window.upper();
        var sources = new ArrayList<Source>();
        for (Message row : rows.subList(0, Math.min(rows.size(), BATCH_SIZE))) {
            sources.add(toSource(work, window.cutoff(), row));
        }
        return new Batch(window, new Selection(through, hasMore, sources));
    }

    static Window planWindow(Work work, RetentionProperties retention) {
        Progress progress = work.member().progress();
        boolean isReset = work.job().type() == JobType.REPLAY;
        Long replayThrough = progress.replayThrough();
        boolean isReplaying = isReset || progress.lastProfiledAt() == null || replayThrough != null;
        long from =
                isReset
                        ? progress.floor()
                        : Math.max(progress.lastProfiledSequence(), progress.floor());
        long upper = replayThrough != null && !isReset ? replayThrough : work.scope().eventSeq();
        int lookback = isReplaying ? work.policy().coldStartLookbackDays() : INCREMENT_DAYS;
        return new Window(
                new Range(from, upper),
                cutoff(work.now(), lookback, retention),
                isReplaying,
                isReset);
    }

    private List<Message> readSources(Work work, Window window) {
        return db.query(
                """
                select id,event_seq,occurred_at,body,reply_to_key from group_message
                where group_id=? and epoch=? and sender_id=? and kind='TEXT' and event_seq>? and event_seq<=?
                  and occurred_at between ? and ? and received_at<=? order by event_seq limit ?
                """,
                MemberProfileEvidence::readMessage,
                work.scope().group(),
                work.scope().epoch(),
                work.sender(),
                window.range().from(),
                window.upper(),
                window.cutoff(),
                work.now(),
                work.now(),
                BATCH_SIZE + 1);
    }

    private static Message readMessage(ResultSet row, int rowNumber) throws SQLException {
        return new Message(
                UUID.fromString(row.getString("id")),
                row.getLong("event_seq"),
                Instant.ofEpochMilli(row.getLong("occurred_at")),
                row.getString("body"),
                row.getString("reply_to_key"));
    }

    private Source toSource(Work work, Instant cutoff, Message row) {
        String content = ProfileGuardrails.input(row.body());
        String background = content.isEmpty() ? "" : findBackground(work, row, cutoff).orElse("");
        return new Source(new Cue(row.id(), row.sequence(), row.occurredAt()), content, background);
    }

    private Optional<String> findBackground(Work work, Message source, Instant cutoff) {
        return findRepliedMessage(work, source, cutoff)
                .or(() -> findEarlierMessage(work, source, cutoff));
    }

    private Optional<String> findRepliedMessage(Work work, Message source, Instant cutoff) {
        if (source.replyToKey() == null) {
            return Optional.empty();
        }
        return db.find(
                FIND_BACKGROUND + " and m.message_key=? limit 1",
                MemberProfileEvidence::readBackground,
                work.scope().group(),
                work.scope().epoch(),
                work.sender(),
                source.sequence(),
                cutoff,
                source.occurredAt(),
                work.now(),
                source.replyToKey());
    }

    private Optional<String> findEarlierMessage(Work work, Message source, Instant cutoff) {
        return db.find(
                FIND_BACKGROUND + " order by m.event_seq desc limit 1",
                MemberProfileEvidence::readBackground,
                work.scope().group(),
                work.scope().epoch(),
                work.sender(),
                source.sequence(),
                cutoff,
                source.occurredAt(),
                work.now());
    }

    private static String readBackground(ResultSet row, int rowNumber) throws SQLException {
        return ProfileGuardrails.cut(
                ProfileGuardrails.input(row.getString("body")), BACKGROUND_CODE_POINTS);
    }

    private static Instant cutoff(Instant now, int days, RetentionProperties retention) {
        Instant window = now.minus(Duration.ofDays(days));
        Instant archive = retention.archiveCutoff(now);
        return window.isAfter(archive) ? window : archive;
    }

    void verifySources(Work work, Set<UUID> sources, Instant now) {
        if (sources.isEmpty()) {
            return;
        }
        long present =
                db.find(
                                """
                select count(*) from json_each(?) s join group_message m on m.id=s.value
                where m.group_id=? and m.epoch=? and m.sender_id=? and m.kind='TEXT' and m.occurred_at>=?
                """,
                                (row, number) -> row.getLong(1),
                                db.json(sources),
                                work.scope().group(),
                                work.scope().epoch(),
                                work.sender(),
                                retention.archiveCutoff(now))
                        .orElseThrow();
        if (present != sources.size()) {
            throw new CancellationException("PROFILE_SOURCES_CHANGED");
        }
    }

    record Progress(
            long lastProfiledSequence, Long replayThrough, Instant lastProfiledAt, long floor) {}

    record Window(Range range, Instant cutoff, boolean isReplaying, boolean isReset) {
        long upper() {
            return range.upper();
        }
    }

    record Range(long from, long upper) {}

    record Batch(Window window, Selection selection) {}

    record Selection(long through, boolean hasMore, List<Source> sources) {
        Selection {
            sources = List.copyOf(sources);
        }
    }

    record Source(Cue cue, String body, String background) {
        UUID id() {
            return cue.id();
        }

        long seq() {
            return cue.sequence();
        }

        Instant when() {
            return cue.occurredAt();
        }
    }

    record Cue(UUID id, long sequence, Instant occurredAt) {}

    private record Message(
            UUID id, long sequence, Instant occurredAt, String body, String replyToKey) {}
}
