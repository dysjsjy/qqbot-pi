/**
 * [INPUT]: 接收读批模型 JSON、可信来源单元和尝试内事实起始编号。
 * [OUTPUT]: 返回可信事实与逐单元处置记录，校验来源与责任人和截止时间。
 * [POS]: 日报读批输出边界。兼容完整与紧凑处置格式，结果独立于尝试状态。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static dev.qqbot.schedule.DailySummaryFacts.invalid;
import static dev.qqbot.schedule.DailySummaryFacts.kind;
import static dev.qqbot.schedule.DailySummaryFacts.ownerGrounded;
import static dev.qqbot.schedule.DailySummaryFacts.value;
import static dev.qqbot.schedule.DailySummaryFacts.valueReason;
import static dev.qqbot.schedule.DailySummaryJson.array;
import static dev.qqbot.schedule.DailySummaryJson.display;
import static dev.qqbot.schedule.DailySummaryJson.fields;
import static dev.qqbot.schedule.DailySummaryJson.parse;
import static dev.qqbot.schedule.DailySummaryJson.string;
import static dev.qqbot.schedule.DailySummaryJson.strings;

import com.fasterxml.jackson.databind.JsonNode;
import dev.qqbot.schedule.DailySummaryFacts.Disposition;
import dev.qqbot.schedule.DailySummaryFacts.Fact;
import dev.qqbot.schedule.DailySummaryFacts.ReadResult;
import dev.qqbot.schedule.DailySummaryFacts.SourceUnit;
import dev.qqbot.schedule.DailySummaryFacts.Value;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class DailySummaryReading {
    private static final String FACTS = "facts";
    private static final String IGNORED = "ignored";
    private static final String DISPOSITIONS = "dispositions";
    private static final String REASON = "reason";
    private static final String CHAT = "CHAT";
    private static final List<String> SKIP_REASONS = List.of(CHAT, "ACK", "NON_SUBSTANTIVE");

    private DailySummaryReading() {}

    static ReadResult read(String raw, List<SourceUnit> units, int nextId) {
        var sources = new Sources(units);
        var root = parse(raw, sources.messageIds());
        fields(root, FACTS, DISPOSITIONS, IGNORED);
        List<Extracted> extracted = readFacts(root, sources, nextId);
        List<Disposition> dispositions = readDispositions(root, sources, extracted);
        return new ReadResult(
                extracted.stream().map(Extracted::fact).toList(), List.copyOf(dispositions));
    }

    private static List<Extracted> readFacts(JsonNode root, Sources sources, int nextId) {
        var extracted = new ArrayList<Extracted>();
        for (JsonNode item : array(root, FACTS)) {
            extracted.add(parseFact(item, sources, "f" + (nextId + extracted.size())));
        }
        return extracted;
    }

    private static Extracted parseFact(JsonNode item, Sources sources, String id) {
        fields(item, "kind", "text", "topic", "owner", "due", "units", "value", REASON);
        List<String> references = strings(item, "units");
        Grounding grounding = sources.resolveEvidence(references);
        String owner = display(item, "owner", 80, true);
        String due = display(item, "due", 80, true);
        String rawOwner = owner.replace("「", "").replace("」", "").replaceFirst("^@+", "").strip();
        grounding.verifyDetails(rawOwner, due);
        Value rating = value(item);
        String reason = valueReason(item, rating);
        var fact =
                new Fact(
                        id,
                        kind(item),
                        display(item, "text", 500, false),
                        display(item, "topic", 60, false),
                        rawOwner,
                        due,
                        grounding.messageIds(),
                        rating,
                        reason);
        return new Extracted(fact, Set.copyOf(references));
    }

    private static List<Disposition> readDispositions(
            JsonNode root, Sources sources, List<Extracted> facts) {
        if (root.has(IGNORED)) {
            if (root.has(DISPOSITIONS)) {
                throw invalid();
            }
            return readCompactDispositions(root.get(IGNORED), sources, facts);
        }
        return readExplicitDispositions(root, sources, facts);
    }

    private static List<Disposition> readCompactDispositions(
            JsonNode ignored, Sources sources, List<Extracted> facts) {
        fields(ignored, SKIP_REASONS.toArray(String[]::new));
        var assigned = new LinkedHashMap<String, Disposition>();
        // 紧凑格式以事实中声明的来源单元为准。显式忽略不能覆盖事实引用。
        for (String id : sources.orderedIds()) {
            var links =
                    facts.stream()
                            .filter(item -> item.units().contains(id))
                            .map(Extracted::id)
                            .toList();
            if (!links.isEmpty()) {
                assigned.put(id, new Disposition(id, links, ""));
            }
        }
        appendSkipped(ignored, sources, assigned);
        if (!assigned.isEmpty()) {
            for (String id : sources.orderedIds()) {
                assigned.putIfAbsent(id, new Disposition(id, List.of(), CHAT));
            }
        }
        verifyUnitCount(sources, assigned);
        return List.copyOf(assigned.values());
    }

    private static void appendSkipped(
            JsonNode ignored, Sources sources, Map<String, Disposition> assigned) {
        for (String reason : SKIP_REASONS) {
            if (!ignored.has(reason)) {
                continue;
            }
            for (String id : strings(ignored, reason)) {
                if (!sources.hasUnit(id)) {
                    throw invalid();
                }
                assigned.putIfAbsent(id, new Disposition(id, List.of(), reason));
            }
        }
    }

    private static List<Disposition> readExplicitDispositions(
            JsonNode root, Sources sources, List<Extracted> facts) {
        var assigned = new LinkedHashMap<String, Disposition>();
        for (JsonNode item : array(root, DISPOSITIONS)) {
            fields(item, "unit", FACTS, REASON);
            String id = string(item, "unit", 120, false);
            if (!sources.hasUnit(id) || assigned.containsKey(id)) {
                throw invalid();
            }
            assigned.put(id, readExplicitDisposition(item, id, facts));
        }
        verifyUnitCount(sources, assigned);
        verifyFactLinks(facts, assigned);
        return List.copyOf(assigned.values());
    }

    private static Disposition readExplicitDisposition(
            JsonNode item, String id, List<Extracted> facts) {
        if (!item.has(FACTS)) {
            String reason = string(item, REASON, 30, false);
            if (!SKIP_REASONS.contains(reason)) {
                throw invalid();
            }
            return new Disposition(id, List.of(), reason);
        }
        if (item.has(REASON)) {
            throw invalid();
        }
        var links = new LinkedHashSet<String>();
        for (var reference : array(item, FACTS)) {
            Extracted fact = requireFactIndex(reference, facts);
            if (!fact.units().contains(id) || !links.add(fact.id())) {
                throw invalid();
            }
        }
        if (links.isEmpty()) {
            throw invalid();
        }
        return new Disposition(id, List.copyOf(links), "");
    }

    private static Extracted requireFactIndex(JsonNode reference, List<Extracted> facts) {
        if (!reference.isInt() || reference.asInt() < 0 || reference.asInt() >= facts.size()) {
            throw invalid();
        }
        return facts.get(reference.asInt());
    }

    private static void verifyUnitCount(Sources sources, Map<String, Disposition> assigned) {
        if (assigned.size() != sources.orderedIds().size()) {
            throw invalid();
        }
    }

    private static void verifyFactLinks(List<Extracted> facts, Map<String, Disposition> assigned) {
        for (Extracted fact : facts) {
            boolean hasMissingLink =
                    fact.units().stream()
                            .anyMatch(id -> !assigned.get(id).facts().contains(fact.id()));
            if (hasMissingLink) {
                throw invalid();
            }
        }
    }

    private record Extracted(Fact fact, Set<String> units) {
        String id() {
            return fact.id();
        }
    }

    private record Grounding(Set<String> messageIds, String text) {
        void verifyDetails(String owner, String due) {
            if (!ownerGrounded(owner, text) || (!due.isEmpty() && !text.contains(due))) {
                throw invalid();
            }
        }
    }

    private static final class Sources {
        private final Map<String, SourceUnit> byId = new LinkedHashMap<>();
        private final List<String> orderedIds;
        private final Set<String> messageIds;

        Sources(List<SourceUnit> units) {
            for (SourceUnit unit : units) {
                byId.put(unit.id(), unit);
            }
            orderedIds = units.stream().map(SourceUnit::id).toList();
            messageIds =
                    units.stream()
                            .map(SourceUnit::messageId)
                            .collect(Collectors.toUnmodifiableSet());
        }

        Grounding resolveEvidence(List<String> ids) {
            if (ids.isEmpty()) {
                throw invalid();
            }
            var messages = new LinkedHashSet<String>();
            var text = new StringBuilder();
            for (String id : ids) {
                SourceUnit unit = byId.get(id);
                if (unit == null) {
                    throw new IllegalStateException("INVALID_CITATION");
                }
                messages.add(unit.messageId());
                text.append(unit.sender()).append('\n');
                text.append(unit.text()).append('\n');
            }
            return new Grounding(messages, text.toString());
        }

        List<String> orderedIds() {
            return orderedIds;
        }

        Set<String> messageIds() {
            return messageIds;
        }

        boolean hasUnit(String id) {
            return byId.containsKey(id);
        }
    }
}
