/**
 * [INPUT]: 接收日报任务冻结的消息及平台成员昵称。
 * [OUTPUT]: 提供单次尝试的有序来源、空文本占位与匿名禁用词。
 * [POS]: 生成前冻结来源；预算不足时只重切当前尝试的单元，不改原消息。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import dev.qqbot.schedule.DailySummaryFacts.SourceMessage;
import dev.qqbot.schedule.DailySummaryFacts.SourceUnit;
import dev.qqbot.schedule.DailySummaryPlan.PlannedStep;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DailySummarySources {
    private static final int MAX_MESSAGES = 1_000;
    private static final int MAX_INPUT_CODE_POINTS = 100_000;
    private static final int UNIT_CODE_POINTS = 600;

    private final Map<String, SourceUnit> units = new LinkedHashMap<>();
    private Set<String> markupOnly = Set.of();
    private Set<String> forbiddenSpeakers = Set.of();
    private int messages;

    void freeze(List<SourceMessage> input, Map<String, String> names) {
        Frozen frozen = freezeSources(input, names);
        // SIDE EFFECT: 原始消息快照保持不变，只有当前尝试的来源允许重切。
        messages = input.size();
        units.putAll(frozen.units());
        markupOnly = frozen.markupOnlyUnits();
        forbiddenSpeakers = Set.copyOf(frozen.forbiddenSpeakers());
    }

    SourceUnit requireUnit(String id) {
        SourceUnit unit = units.get(id);
        if (unit == null) {
            throw new IllegalStateException(
                    "SUMMARY_WORKFLOW_INVARIANT",
                    new IllegalArgumentException("Unknown source unit: unitId=" + id));
        }
        return unit;
    }

    void replace(String original, Split split) {
        requireUnit(original);
        // SIDE EFFECT: 预算重排只替换当前尝试的原单元。
        units.remove(original);
        units.put(split.left().id(), split.left());
        units.put(split.right().id(), split.right());
    }

    boolean isMarkupOnly(PlannedStep step) {
        return step.ids().stream().allMatch(markupOnly::contains);
    }

    List<SourceUnit> substantive(PlannedStep step) {
        return step.ids().stream()
                .filter(id -> !markupOnly.contains(id))
                .map(this::requireUnit)
                .toList();
    }

    List<SourceUnit> select(PlannedStep step) {
        return step.ids().stream().map(this::requireUnit).toList();
    }

    Map<String, SourceUnit> byId() {
        return Collections.unmodifiableMap(units);
    }

    Collection<SourceUnit> ordered() {
        return Collections.unmodifiableCollection(units.values());
    }

    Set<String> forbiddenSpeakers() {
        return forbiddenSpeakers;
    }

    int messages() {
        return messages;
    }

    int size() {
        return units.size();
    }

    static Frozen freezeSources(List<SourceMessage> messages, Map<String, String> memberNames) {
        validateSources(messages);
        var units = new LinkedHashMap<String, SourceUnit>();
        var markupOnlyUnits = new HashSet<String>();
        var senders = new LinkedHashSet<String>();
        for (int order = 0; order < messages.size(); order++) {
            SourceMessage message = messages.get(order);
            if (!message.sender().isBlank()) {
                senders.add(message.sender());
            }
            List<SourceUnit> fragments = splitSource(message, order, memberNames);
            for (SourceUnit fragment : fragments) {
                units.put(fragment.id(), fragment);
                if (fragment.text().isEmpty()) {
                    markupOnlyUnits.add(fragment.id());
                }
            }
        }
        return new Frozen(
                units, markupOnlyUnits, DailySummaryPreprocessor.forbiddenSpeakerNames(senders));
    }

    private static void validateSources(List<SourceMessage> messages) {
        if (messages.size() > MAX_MESSAGES || inputLength(messages) > MAX_INPUT_CODE_POINTS) {
            throw new IllegalStateException("INPUT_LIMIT");
        }
        var seen = new HashSet<String>();
        for (SourceMessage message : messages) {
            if (!seen.add(message.messageId())) {
                throw new IllegalStateException(
                        "SUMMARY_WORKFLOW_INVARIANT",
                        new IllegalArgumentException(
                                "Duplicate source: messageId=" + message.messageId()));
            }
        }
    }

    private static long inputLength(List<SourceMessage> messages) {
        return messages.stream()
                .map(SourceMessage::text)
                .mapToLong(DailySummarySources::codePoints)
                .sum();
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    private static List<SourceUnit> splitSource(
            SourceMessage message, int order, Map<String, String> memberNames) {
        String cleaned = DailySummaryPreprocessor.cleanText(message.text(), memberNames);
        var source = new CleanedSource(message, order, cleaned);
        int length = codePoints(cleaned);
        var fragments = new ArrayList<SourceUnit>();
        for (int start = 0; start < Math.max(1, length); start += UNIT_CODE_POINTS) {
            fragments.add(unit(source, start, Math.min(length, start + UNIT_CODE_POINTS)));
        }
        return fragments;
    }

    private static SourceUnit unit(CleanedSource source, int start, int end) {
        String id = "u" + source.order() + (start == 0 ? "" : "_" + start);
        String text = source.text();
        SourceMessage message = source.message();
        return new SourceUnit(
                id,
                message.messageId(),
                source.order(),
                start,
                end,
                message.time(),
                message.sender(),
                text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end)));
    }

    static Split bisectSource(SourceUnit source) {
        int length = source.end() - source.start();
        if (length < 2) {
            throw new IllegalStateException("CONTEXT_TOO_LARGE");
        }
        int half = length / 2;
        int cut = source.text().offsetByCodePoints(0, half);
        int middle = source.start() + half;
        return new Split(
                fragment(
                        source,
                        "a",
                        new Slice(source.start(), middle, source.text().substring(0, cut))),
                fragment(
                        source,
                        "b",
                        new Slice(middle, source.end(), source.text().substring(cut))));
    }

    private static SourceUnit fragment(SourceUnit original, String suffix, Slice slice) {
        return new SourceUnit(
                original.id() + suffix,
                original.messageId(),
                original.order(),
                slice.start(),
                slice.end(),
                original.time(),
                original.sender(),
                slice.text());
    }

    record Split(SourceUnit left, SourceUnit right) {}

    private record Slice(int start, int end, String text) {}

    private record CleanedSource(SourceMessage message, int order, String text) {}

    record Frozen(
            Map<String, SourceUnit> units,
            Set<String> markupOnlyUnits,
            Set<String> forbiddenSpeakers) {
        Frozen {
            // 读批按来源顺序规划；工作流重切自己的副本，不改原快照。
            units = Collections.unmodifiableMap(new LinkedHashMap<>(units));
            markupOnlyUnits = Set.copyOf(markupOnlyUnits);
            forbiddenSpeakers = Collections.unmodifiableSet(new LinkedHashSet<>(forbiddenSpeakers));
        }
    }
}
