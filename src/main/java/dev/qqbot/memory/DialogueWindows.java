/**
 * [INPUT]: 接收按会话顺序排列的消息，以及条数、码点和间隔上限。
 * [OUTPUT]: 过滤噪声和短时重复，生成包含来源 ID 的对话窗口。
 * [POS]: 纯窗口切分规则。容量切分保留少量前文，间隔切分隔离会话。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static dev.qqbot.memory.RetrievalGate.*;

import dev.qqbot.memory.RetrievalGate.DialogueMessage;
import dev.qqbot.memory.RetrievalGate.DialogueWindow;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

final class DialogueWindows {
    private static final int MIN_WINDOW_CODE_POINTS = 200;
    private static final int RECENT_ACCEPTED_LIMIT = 3;
    private static final long REPEAT_WINDOW_SECONDS = 120;
    private static final int MAX_OVERLAP_MESSAGES = 2;
    private static final int MAX_OVERLAP_CODE_POINTS = 150;

    private DialogueWindows() {}

    static List<DialogueWindow> build(List<DialogueMessage> messages, Limits limits) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return partition(removeNoise(messages), limits);
    }

    private static List<DialogueMessage> removeNoise(List<DialogueMessage> messages) {
        List<DialogueMessage> valid = new ArrayList<>();
        Deque<DialogueMessage> recent = new ArrayDeque<>();
        for (DialogueMessage message : messages) {
            if (!isUsable(message, recent)) {
                continue;
            }
            valid.add(message);
            recent.addLast(message);
            if (recent.size() > RECENT_ACCEPTED_LIMIT) {
                recent.removeFirst();
            }
        }
        return valid;
    }

    private static boolean isUsable(DialogueMessage message, Deque<DialogueMessage> recent) {
        if (message == null || isAlwaysNoise(message.body()) || isRepeat(message, recent)) {
            return false;
        }
        DialogueMessage previous = recent.peekLast();
        return !isShortAck(message.body()) || previous != null && isNear(previous, message);
    }

    private static boolean isRepeat(DialogueMessage message, Deque<DialogueMessage> recent) {
        String key = normalizeNoiseKey(message.body());
        for (DialogueMessage previous : recent) {
            if (isNear(previous, message) && key.equals(normalizeNoiseKey(previous.body()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNear(DialogueMessage previous, DialogueMessage message) {
        long difference =
                Duration.between(previous.occurredAt(), message.occurredAt()).getSeconds();
        return Math.abs(difference) <= REPEAT_WINDOW_SECONDS;
    }

    private static List<DialogueWindow> partition(List<DialogueMessage> messages, Limits limits) {
        List<DialogueWindow> result = new ArrayList<>();
        Group current = new Group(List.of());
        for (DialogueMessage message : messages) {
            int length = codePoints(formatLine(message));
            boolean isGap = current.hasSessionGap(message, limits);
            if (isGap || current.isFull(length, limits)) {
                result.add(current.seal());
                current = new Group(isGap ? List.of() : tailOverlap(current.messages));
            }
            current.add(message, length);
        }
        if (!current.messages.isEmpty()) {
            result.add(current.seal());
        }
        return List.copyOf(result);
    }

    private static List<DialogueMessage> tailOverlap(List<DialogueMessage> messages) {
        List<DialogueMessage> tail = new ArrayList<>();
        int count = 0;
        for (int index = messages.size() - 1;
                index >= 0 && tail.size() < MAX_OVERLAP_MESSAGES;
                index--) {
            DialogueMessage message = messages.get(index);
            count += codePoints(message.body());
            if (count > MAX_OVERLAP_CODE_POINTS) {
                break;
            }
            tail.add(0, message);
        }
        return List.copyOf(tail);
    }

    private static String formatLine(DialogueMessage message) {
        String shortId = message.id().toString().substring(0, 8);
        return "[" + message.senderName() + " #" + shortId + "] " + message.body();
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    record Limits(int messages, int codePoints, long gapSeconds) {
        Limits {
            messages = Math.max(1, messages);
            codePoints = Math.max(MIN_WINDOW_CODE_POINTS, codePoints);
            gapSeconds = Math.max(1, gapSeconds);
        }
    }

    private static final class Group {
        private final List<DialogueMessage> overlap;
        private final List<DialogueMessage> messages = new ArrayList<>();
        private int codePoints;

        Group(List<DialogueMessage> overlap) {
            this.overlap = overlap;
        }

        void add(DialogueMessage message, int length) {
            messages.add(message);
            codePoints += length + 1;
        }

        boolean hasSessionGap(DialogueMessage next, Limits limits) {
            if (messages.isEmpty()) {
                return false;
            }
            var gap = Duration.between(last().occurredAt(), next.occurredAt());
            return gap.getSeconds() > limits.gapSeconds();
        }

        boolean isFull(int nextLength, Limits limits) {
            return !messages.isEmpty()
                    && (messages.size() >= limits.messages()
                            || codePoints + nextLength > limits.codePoints());
        }

        DialogueWindow seal() {
            DialogueMessage first = messages.get(0);
            List<DialogueMessage> members = new ArrayList<>(overlap);
            members.addAll(messages);
            var ids = new LinkedHashSet<UUID>();
            var lines = new ArrayList<String>();
            for (DialogueMessage member : members) {
                ids.add(member.id());
                lines.add(formatLine(member));
            }
            String body = members.size() == 1 ? first.body() : String.join("\n", lines);
            return new DialogueWindow(
                    first.id(), List.copyOf(ids), body, first.occurredAt(), last().occurredAt());
        }

        private DialogueMessage last() {
            return messages.get(messages.size() - 1);
        }
    }
}
