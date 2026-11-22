/**
 * [INPUT]: 依赖当前群文本、固定统计窗口和成员清除水位。
 * [OUTPUT]: 返回脱敏显示名、别名、活跃窗口与建模准入计数。
 * [POS]: 为画像席位选择读取并计算 L0 活跃度。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static dev.qqbot.memory.MemberProfilePolicyService.ZONE;

import dev.qqbot.memory.MemberProfilePolicyService.Scope;
import dev.qqbot.persistence.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class MemberProfileActivity {
    static final String ACTIVITY =
            """
            group_id=? and epoch=? and kind='TEXT' and sender_id<>''
                and occurred_at between ? and ? and received_at<=?
            """;

    private static final int HOURS_PER_PERIOD = 6;
    private static final int MAX_ALIASES = 3;
    private static final int MAX_DISPLAY_NAME_CODE_POINTS = 48;
    private static final int SHORT_MESSAGE_CODE_POINTS = 20;
    private static final long SHORT_BURST_SECONDS = 60;
    static final String ANONYMOUS_MEMBER = "成员";

    private final Db db;

    MemberProfileActivity(Db db) {
        this.db = db;
    }

    Snapshot collect(Scope scope, Window window, Candidate candidate) {
        return summarize(window, candidate, readMessages(scope, window, candidate));
    }

    private List<Message> readMessages(Scope scope, Window window, Candidate candidate) {
        return db.query(
                "select sender_name,body,occurred_at from group_message where "
                        + ACTIVITY
                        + " and sender_id=? and event_seq>? order by occurred_at,event_seq,id",
                (row, index) -> readMessage(row),
                scope.group(),
                scope.epoch(),
                window.earliest(),
                window.now(),
                window.now(),
                candidate.sender(),
                candidate.sourceFloor());
    }

    private static Message readMessage(ResultSet row) throws SQLException {
        return new Message(
                row.getString("sender_name"),
                row.getString("body"),
                Instant.ofEpochMilli(row.getLong("occurred_at")));
    }

    static Snapshot summarize(Window window, Candidate candidate, List<Message> messages) {
        var activity = new ActivityCounts();
        Instant admissionStart = window.admissionStart(candidate.hasColdStart());
        for (Message message : messages) {
            if (!ProfileGuardrails.input(message.body()).isEmpty()) {
                activity.include(message, window, candidate.sender(), admissionStart);
            }
        }
        return activity.snapshot(candidate.sender());
    }

    static String display(String name, String sender) {
        if (name == null || name.isBlank() || name.equals(sender)) {
            return ANONYMOUS_MEMBER;
        }
        try {
            return ProfileGuardrails.cleanField(
                    ProfileGuardrails.cut(name, MAX_DISPLAY_NAME_CODE_POINTS),
                    MAX_DISPLAY_NAME_CODE_POINTS);
        } catch (IllegalArgumentException rejected) {
            return ANONYMOUS_MEMBER;
        }
    }

    record Window(Instant now, Instant month, Instant quarter, Instant coldStart) {
        static Window endingAt(Instant now, int coldStartDays) {
            return new Window(
                    now,
                    now.minus(Duration.ofDays(30)),
                    now.minus(Duration.ofDays(90)),
                    now.minus(Duration.ofDays(coldStartDays)));
        }

        Instant earliest() {
            return coldStart.isBefore(quarter) ? coldStart : quarter;
        }

        Instant admissionStart(boolean hasColdStart) {
            return hasColdStart ? coldStart : month;
        }
    }

    record Candidate(String sender, long sourceFloor, boolean hasColdStart) {}

    record Message(String senderName, String body, Instant occurredAt) {}

    record Snapshot(
            String sender,
            List<String> aliases,
            Metrics metrics,
            Instant lastActive,
            int admitted,
            int admissionDays) {
        String displayName() {
            return aliases.isEmpty() ? ANONYMOUS_MEMBER : aliases.get(0);
        }

        int monthCount() {
            return metrics.messages30d();
        }
    }

    record Metrics(
            int messages30d,
            int messages90d,
            int activeDays30d,
            List<Integer> periodCounts,
            double averageCharacters,
            double shortBurstRatio) {}

    private static final class ActivityCounts {
        private final Set<LocalDate> activeDays = new HashSet<>();
        private final Set<LocalDate> admissionDays = new HashSet<>();
        private final Set<String> aliases = new LinkedHashSet<>();
        private final int[] periods = new int[24 / HOURS_PER_PERIOD];
        private int monthCount;
        private int quarterCount;
        private int admitted;
        private int shortPairs;
        private int characters;
        private int previousLength;
        private Instant lastActive = Instant.EPOCH;
        private Instant previous;

        private void include(Message message, Window window, String sender, Instant admission) {
            Instant occurredAt = message.occurredAt();
            ZonedDateTime localTime = occurredAt.atZone(ZONE);
            lastActive = occurredAt;
            if (!occurredAt.isBefore(admission)) {
                admitted++;
                admissionDays.add(localTime.toLocalDate());
            }
            if (!occurredAt.isBefore(window.quarter())) {
                quarterCount++;
                addAlias(display(message.senderName(), sender));
            }
            if (!occurredAt.isBefore(window.month())) {
                includeMonth(message, localTime);
            }
        }

        private void addAlias(String name) {
            if (!name.equals(ANONYMOUS_MEMBER)) {
                aliases.remove(name);
                aliases.add(name);
            }
        }

        private void includeMonth(Message message, ZonedDateTime localTime) {
            int length = message.body().codePointCount(0, message.body().length());
            monthCount++;
            activeDays.add(localTime.toLocalDate());
            periods[localTime.getHour() / HOURS_PER_PERIOD]++;
            characters += length;
            if (isShortPair(message.occurredAt(), length)) {
                shortPairs++;
            }
            previous = message.occurredAt();
            previousLength = length;
        }

        private boolean isShortPair(Instant time, int length) {
            return previous != null
                    && previousLength <= SHORT_MESSAGE_CODE_POINTS
                    && length <= SHORT_MESSAGE_CODE_POINTS
                    && Duration.between(previous, time).getSeconds() <= SHORT_BURST_SECONDS;
        }

        private Snapshot snapshot(String sender) {
            var names = new ArrayList<>(aliases);
            Collections.reverse(names);
            var metrics =
                    new Metrics(
                            monthCount,
                            quarterCount,
                            activeDays.size(),
                            List.of(periods[0], periods[1], periods[2], periods[3]),
                            monthCount == 0 ? 0 : (double) characters / monthCount,
                            monthCount < 2 ? 0 : (double) shortPairs / (monthCount - 1));
            return new Snapshot(
                    sender,
                    names.stream().limit(MAX_ALIASES).toList(),
                    metrics,
                    lastActive,
                    admitted,
                    admissionDays.size());
        }
    }
}
