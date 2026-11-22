/**
 * [INPUT]: 接收整理或编辑阶段的模型 JSON、可信事实及编号和署名规则。
 * [OUTPUT]: 返回来源闭合的修订、受审阅约束的事实及编辑阶段的选材记录。
 * [POS]: 日报编辑输出边界。模型结果通过全批验证后才能替换尝试内事实。
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
import dev.qqbot.schedule.DailySummaryFacts.Choice;
import dev.qqbot.schedule.DailySummaryFacts.Edited;
import dev.qqbot.schedule.DailySummaryFacts.Fact;
import dev.qqbot.schedule.DailySummaryFacts.InvalidResult;
import dev.qqbot.schedule.DailySummaryFacts.Kind;
import dev.qqbot.schedule.DailySummaryFacts.Organized;
import dev.qqbot.schedule.DailySummaryFacts.Revision;
import dev.qqbot.schedule.DailySummaryFacts.Value;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DailySummaryEditing {
    private static final String FACTS = "facts";
    private static final String RELATION = "relation";
    private static final String TEXT = "text";
    private static final String VALUE = "value";
    private static final String REASON = "reason";
    private static final String REVIEW = "review";
    private static final String DETAIL = "detail";
    private static final int MIN_REVIEW_CODE_POINTS = 8;
    private static final Set<String> DETAIL_LEVELS = Set.of("FULL", "BRIEF");

    private DailySummaryEditing() {}

    static Edited revise(String raw, List<Fact> inputs, Rules rules) {
        var original = new Originals(inputs);
        var root = parse(raw, DailySummaryFacts.sources(inputs));
        fields(root, FACTS);
        var accepted = new ArrayList<Change>();
        for (JsonNode item : array(root, FACTS)) {
            accepted.add(
                    readChange(item, original, rules, "f" + (rules.nextId() + accepted.size())));
        }
        original.verifyCoverage();
        return assembleEdit(accepted, rules.isEditorial());
    }

    private static Change readChange(JsonNode item, Originals originals, Rules rules, String id) {
        fields(
                item, "inputs", RELATION, "kind", TEXT, "topic", "owner", "due", VALUE, REASON,
                REVIEW, DETAIL);
        List<String> references = strings(item, "inputs");
        if (references.isEmpty()) {
            throw invalid();
        }
        Relation relation = readRelation(item);
        var sources = originals.resolve(references);
        boolean isCompact =
                rules.isEditorial()
                        && relation == Relation.KEEP
                        && references.size() == 1
                        && !item.has(TEXT);
        var details = readDetails(item, sources.get(0), isCompact);
        Rating rating = readRating(item, sources, rules.isEditorial());
        verifyReview(item, new Review(rating, sources), isCompact, rules.isEditorial());
        verifyGrounding(details, sources);
        Content content = readContent(item, sources.get(0), isCompact, details);
        verifyRequiredDetails(relation, content, sources, rules.isAnonymous());
        return buildChange(
                item, new Draft(relation, sources, content, rating), id, rules.isEditorial());
    }

    private static Change buildChange(JsonNode item, Draft draft, String id, boolean isEditorial) {
        List<Fact> sources = draft.sources();
        Content content = draft.content();
        boolean isIdentical = sources.size() == 1 && content.matches(sources.get(0));
        verifyRelation(
                draft.relation(),
                content.details().kind(),
                sources.size(),
                isIdentical || isEditorial);
        Rating rating = draft.rating();
        String detail = isEditorial ? readDetail(item, rating.value()) : "";
        var fact = buildFact(id, draft, isIdentical);
        var revision =
                new Revision(
                        sources.stream().map(Fact::id).toList(),
                        fact.id(),
                        draft.relation().name(),
                        DailySummaryFacts.sources(sources),
                        rating.review());
        return new Change(fact, revision, detail, !isIdentical);
    }

    private static Relation readRelation(JsonNode item) {
        if (!item.has(RELATION)) {
            return Relation.KEEP;
        }
        try {
            return Relation.valueOf(string(item, RELATION, 20, false));
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static Details readDetails(JsonNode item, Fact first, boolean isCompact) {
        if (isCompact) {
            return new Details(first.kind(), first.owner(), first.due());
        }
        return new Details(
                kind(item), display(item, "owner", 80, true), display(item, "due", 80, true));
    }

    private static Rating readRating(JsonNode item, List<Fact> sources, boolean isEditorial) {
        Fact first = sources.get(0);
        Value rating = isEditorial ? readEditorialValue(item, first) : inheritedValue(sources);
        String reason =
                isEditorial && item.has(REASON) ? valueReason(item, rating) : first.reason();
        String review = item.has(REVIEW) ? display(item, REVIEW, 120, true) : "";
        return new Rating(rating, reason, review);
    }

    private static Value readEditorialValue(JsonNode item, Fact first) {
        return item.has(VALUE) ? value(item) : first.value();
    }

    private static Value inheritedValue(List<Fact> sources) {
        return sources.stream().anyMatch(Fact::critical) ? Value.REQUIRED : Value.OPTIONAL;
    }

    private static void verifyReview(
            JsonNode item, Review review, boolean isCompact, boolean isEditorial) {
        if (!isEditorial) {
            return;
        }
        if ((!isCompact && !item.has(VALUE))
                || ((item.has(VALUE) || !isCompact) && !item.has(REASON))) {
            throw invalid();
        }
        Rating rating = review.rating();
        int length = rating.review().codePointCount(0, rating.review().length());
        if (rating.value() != Value.REQUIRED
                && length < MIN_REVIEW_CODE_POINTS
                && review.originals().stream().anyMatch(Fact::critical)) {
            throw requiredFactMissing(review.originals());
        }
    }

    private static void verifyGrounding(Details details, List<Fact> originals) {
        var evidence = new StringBuilder();
        for (Fact original : originals) {
            evidence.append(original.owner()).append('\n');
            evidence.append(original.text()).append('\n');
        }
        if (!ownerGrounded(details.owner(), evidence.toString())) {
            throw invalid();
        }
        String due = details.due();
        if (!due.isEmpty()
                && originals.stream()
                        .noneMatch(fact -> fact.due().equals(due) || fact.text().contains(due))) {
            throw invalid();
        }
    }

    private static Content readContent(
            JsonNode item, Fact first, boolean isCompact, Details details) {
        if (isCompact) {
            return new Content(details, first.text(), first.topic());
        }
        return new Content(
                details, display(item, TEXT, 500, false), display(item, "topic", 60, false));
    }

    private static void verifyRequiredDetails(
            Relation relation, Content content, List<Fact> sources, boolean isAnonymous) {
        if (relation == Relation.CORRECT) {
            return;
        }
        for (Fact fact : sources) {
            if (fact.critical() && content.lacksDetailsFrom(fact, isAnonymous)) {
                throw requiredFactMissing(sources);
            }
        }
    }

    private static InvalidResult requiredFactMissing(List<Fact> sources) {
        return new InvalidResult(
                "REQUIRED_FACT_MISSING", sources.stream().map(Fact::id).toList(), 0, 1);
    }

    private static void verifyRelation(
            Relation relation, Kind kind, int originals, boolean canKeep) {
        if (relation == Relation.CONFLICT && kind != Kind.DISPUTE && kind != Kind.OPEN_QUESTION) {
            throw invalid();
        }
        if (relation == Relation.KEEP && (originals != 1 || !canKeep)) {
            throw invalid();
        }
    }

    private static Fact buildFact(String id, Draft draft, boolean isIdentical) {
        Fact first = draft.sources().get(0);
        Rating rating = draft.rating();
        if (isIdentical
                && first.value() == rating.value()
                && first.reason().equals(rating.reason())) {
            return first;
        }
        Content content = draft.content();
        Details details = content.details();
        return new Fact(
                id,
                details.kind(),
                content.text(),
                content.topic(),
                details.owner(),
                details.due(),
                DailySummaryFacts.sources(draft.sources()),
                rating.value(),
                rating.reason());
    }

    private static String readDetail(JsonNode item, Value value) {
        String detail = string(item, DETAIL, 10, true);
        boolean isValid = value == Value.OMIT ? detail.isEmpty() : DETAIL_LEVELS.contains(detail);
        if (!isValid) {
            throw invalid();
        }
        return detail;
    }

    private static Edited assembleEdit(List<Change> changes, boolean isEditorial) {
        var organized =
                new Organized(
                        changes.stream().map(Change::fact).toList(),
                        changes.stream().map(Change::revision).toList(),
                        changes.stream().anyMatch(Change::isChanged));
        var choices =
                isEditorial ? changes.stream().map(Change::choice).toList() : List.<Choice>of();
        return new Edited(organized, choices);
    }

    record Rules(int nextId, boolean isEditorial, boolean isAnonymous) {
        static Rules forEditing(int nextId, SummaryProfile profile) {
            boolean isAnonymous =
                    profile != null
                            && profile.speakerMode() == SummaryProfile.SpeakerMode.ANONYMOUS;
            return new Rules(nextId, true, isAnonymous);
        }
    }

    private enum Relation {
        KEEP,
        MERGE,
        CORRECT,
        CONFLICT
    }

    private record Rating(Value value, String reason, String review) {}

    private record Review(Rating rating, List<Fact> originals) {}

    private record Details(Kind kind, String owner, String due) {}

    private record Draft(Relation relation, List<Fact> sources, Content content, Rating rating) {}

    private record Content(Details details, String text, String topic) {
        boolean matches(Fact first) {
            return first.kind() == details.kind()
                    && first.text().equals(text)
                    && first.topic().equals(topic)
                    && first.owner().equals(details.owner())
                    && first.due().equals(details.due());
        }

        boolean lacksDetailsFrom(Fact fact, boolean isAnonymous) {
            return (!isAnonymous && isDropped(fact.owner(), details.owner()))
                    || isDropped(fact.due(), details.due());
        }

        private boolean isDropped(String before, String after) {
            return !before.isEmpty() && !before.equals(after) && !text.contains(before);
        }
    }

    private record Change(Fact fact, Revision revision, String detail, boolean isChanged) {
        Choice choice() {
            return new Choice(fact.id(), detail, fact.value() == Value.OMIT ? fact.reason() : "");
        }
    }

    private static final class Originals {
        private final Map<String, Fact> byId = new LinkedHashMap<>();
        private final Set<String> seen = new HashSet<>();
        private final int expected;

        Originals(List<Fact> originals) {
            this.expected = originals.size();
            for (Fact fact : originals) {
                byId.put(fact.id(), fact);
            }
        }

        List<Fact> resolve(List<String> references) {
            var originals = new ArrayList<Fact>();
            for (String reference : references) {
                Fact fact = byId.get(reference);
                if (fact == null) {
                    throw new IllegalStateException("INVALID_CITATION");
                }
                if (!seen.add(reference)) {
                    throw invalid();
                }
                originals.add(fact);
            }
            return originals;
        }

        void verifyCoverage() {
            if (seen.size() != expected) {
                throw invalid();
            }
        }
    }
}
