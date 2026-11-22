/**
 * [INPUT]: 接收可信来源单元、事实字段和证据文本。
 * [OUTPUT]: 提供日报结果值对象、事实评分及证据归属检查。
 * [POS]: 各阶段共享可信事实身份，选材与成文各自校验覆盖率和输出格式。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static dev.qqbot.schedule.DailySummaryJson.string;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Attempt-local, trusted identities and validation of untrusted semantic results. */
public final class DailySummaryFacts {
    private DailySummaryFacts() {}

    public record SourceMessage(String messageId, String time, String sender, String text) {
        public SourceMessage {
            UUID.fromString(messageId);
            Objects.requireNonNull(text);
            Objects.requireNonNull(time);
            sender = sender == null ? "" : sender;
            if (sender.codePointCount(0, sender.length()) > 40) {
                sender = sender.substring(0, sender.offsetByCodePoints(0, 40));
            }
        }
    }

    public record SourceUnit(
            String id,
            String messageId,
            int order,
            int start,
            int end,
            String time,
            String sender,
            String text) {}

    public enum Kind {
        TOPIC,
        CONCLUSION,
        ACTION,
        OPEN_QUESTION,
        DISPUTE
    }

    public enum Value {
        REQUIRED,
        OPTIONAL,
        OMIT
    }

    private static final Set<String> VALUE_REASONS =
            Set.of(
                    "COMMITMENT",
                    "DEADLINE",
                    "DECISION",
                    "UNRESOLVED",
                    "USEFUL",
                    "LOW_VALUE",
                    "BACKGROUND",
                    "OFF_TOPIC");

    public record Fact(
            String id,
            Kind kind,
            String text,
            String topic,
            String owner,
            String due,
            Set<String> sources,
            Value value,
            String reason) {
        public Fact {
            sources = Collections.unmodifiableSet(new LinkedHashSet<>(sources));
        }

        public Fact(
                String id,
                Kind kind,
                String text,
                String topic,
                String owner,
                String due,
                Set<String> sources) {
            this(id, kind, text, topic, owner, due, sources, Value.OPTIONAL, "USEFUL");
        }

        boolean critical() {
            return value == Value.REQUIRED;
        }
    }

    public record Disposition(String unit, List<String> facts, String reason) {
        public Disposition {
            facts = List.copyOf(facts);
        }
    }

    public record ReadResult(List<Fact> facts, List<Disposition> dispositions) {}

    public record Revision(
            List<String> inputs,
            String output,
            String relation,
            Set<String> sources,
            String review) {
        public Revision {
            inputs = List.copyOf(inputs);
            sources = Set.copyOf(sources);
        }
    }

    public record Organized(List<Fact> facts, List<Revision> revisions, boolean changed) {}

    public record Edited(Organized organized, List<Choice> choices) {}

    public record Choice(String fact, String detail, String omission) {}

    public record Paragraph(String text, List<String> facts, Set<String> sources) {
        public Paragraph {
            facts = List.copyOf(facts);
            sources = Set.copyOf(sources);
        }
    }

    public record WriteResult(String lead, List<Paragraph> paragraphs) {
        public WriteResult {
            lead = lead == null ? "" : lead;
            paragraphs = List.copyOf(paragraphs);
        }
    }

    public record Feedback(String code, List<String> objects, int observed, int target) {
        public Feedback {
            objects = List.copyOf(objects);
        }
    }

    static final class InvalidResult extends RuntimeException {
        final Feedback feedback;

        InvalidResult(String code) {
            this(code, List.of(), 0, 0);
        }

        InvalidResult(String code, List<String> objects, int observed, int target) {
            super(code);
            feedback = new Feedback(code, objects, observed, target);
        }
    }

    static InvalidResult invalid() {
        return new InvalidResult("RESPONSE_FORMAT");
    }

    static Kind kind(JsonNode node) {
        try {
            return Kind.valueOf(string(node, "kind", 20, false));
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    static Set<String> sources(Collection<Fact> facts) {
        var result = new LinkedHashSet<String>();
        for (Fact fact : facts) {
            result.addAll(fact.sources());
        }
        return result;
    }

    static boolean ownerGrounded(String owner, String evidence) {
        if (owner.isEmpty() || evidence.contains(owner)) {
            return true;
        }
        int matched = 0;
        for (String part : owner.split("[、,，/;&；]|\\s+(?:和|与|及)\\s+")) {
            String p = part.replace("「", "").replace("」", "").replaceFirst("^@+", "").strip();
            if (p.isEmpty()) {
                continue;
            }
            if (!evidence.contains(p)) {
                return false;
            }
            matched++;
        }
        return matched > 0;
    }

    static Value value(JsonNode item) {
        try {
            return item.has("value")
                    ? Value.valueOf(string(item, "value", 20, false))
                    : Value.OPTIONAL;
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    static String valueReason(JsonNode item, Value value) {
        String reason = item.has("reason") ? string(item, "reason", 30, false) : "USEFUL";
        if (!VALUE_REASONS.contains(reason)) {
            throw invalid();
        }
        if (value == Value.REQUIRED
                && !Set.of("COMMITMENT", "DEADLINE", "DECISION", "UNRESOLVED", "USEFUL")
                        .contains(reason)) {
            throw invalid();
        }
        if (value == Value.OMIT
                && !Set.of("LOW_VALUE", "BACKGROUND", "OFF_TOPIC").contains(reason)) {
            throw invalid();
        }
        return reason;
    }
}
