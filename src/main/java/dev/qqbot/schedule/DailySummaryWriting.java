/**
 * [INPUT]: 接收写作阶段的原始 JSON、已选事实与字数和署名规则。
 * [OUTPUT]: 返回通过引文、覆盖率、正文和署名约束的导语与段落。
 * [POS]: 日报成文输出边界，验证通过后由 DailySummaryMaterial 写入尝试状态。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static dev.qqbot.schedule.DailySummaryFacts.invalid;
import static dev.qqbot.schedule.DailySummaryJson.array;
import static dev.qqbot.schedule.DailySummaryJson.checkCitations;
import static dev.qqbot.schedule.DailySummaryJson.fields;
import static dev.qqbot.schedule.DailySummaryJson.parse;
import static dev.qqbot.schedule.DailySummaryJson.string;
import static dev.qqbot.schedule.DailySummaryJson.strings;

import com.fasterxml.jackson.databind.JsonNode;
import dev.qqbot.schedule.DailySummaryFacts.Fact;
import dev.qqbot.schedule.DailySummaryFacts.InvalidResult;
import dev.qqbot.schedule.DailySummaryFacts.Paragraph;
import dev.qqbot.schedule.DailySummaryFacts.WriteResult;
import dev.qqbot.text.Texts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DailySummaryWriting {
    private static final int MAX_LEAD_CODE_POINTS = 120;
    private static final int MAX_PARAGRAPH_CODE_POINTS = 4_000;
    private static final String LEAD = "lead";
    private static final String FACTS = "facts";
    private static final String PARAGRAPHS = "paragraphs";
    private static final String SOURCES = "sources";
    private static final String TEXT = "text";
    private static final String REQUIRED_FACT_MISSING = "REQUIRED_FACT_MISSING";
    private static final String INVALID_CITATION = "INVALID_CITATION";

    private DailySummaryWriting() {}

    static WriteResult validate(String raw, List<Fact> inputs, Rules rules) {
        Set<String> sources = DailySummaryFacts.sources(inputs);
        var root = parse(raw, sources);
        fields(root, LEAD, PARAGRAPHS);
        String lead = readLead(root, sources, rules);
        var coverage = new Coverage(inputs);
        List<Paragraph> paragraphs = readParagraphs(root, coverage, rules);
        coverage.verifyCoverage();
        verifyLength(lead, paragraphs, rules.target());
        return new WriteResult(paragraphs.size() >= 2 ? lead : "", paragraphs);
    }

    private static String readLead(JsonNode root, Set<String> allowed, Rules rules) {
        if (!root.has(LEAD) || root.get(LEAD).isNull()) {
            return "";
        }
        String lead =
                Texts.plainText(string(root, LEAD, MAX_LEAD_CODE_POINTS, true))
                        .replace('\n', ' ')
                        .strip();
        if (!lead.isBlank()) {
            checkCitations(lead, allowed);
            verifyProse(lead);
            rules.verifyAnonymity(lead, List.of());
        }
        return lead;
    }

    private static List<Paragraph> readParagraphs(JsonNode root, Coverage coverage, Rules rules) {
        var paragraphs = new ArrayList<Paragraph>();
        for (JsonNode item : array(root, PARAGRAPHS)) {
            paragraphs.add(readParagraph(item, coverage, rules));
        }
        return List.copyOf(paragraphs);
    }

    private static Paragraph readParagraph(JsonNode item, Coverage coverage, Rules rules) {
        fields(item, TEXT, FACTS, SOURCES);
        List<String> references = strings(item, FACTS);
        if (references.isEmpty()) {
            throw invalid();
        }
        List<Fact> facts = coverage.resolve(references);
        Set<String> permitted = DailySummaryFacts.sources(facts);
        Set<String> cited = new LinkedHashSet<>(strings(item, SOURCES));
        verifyCitedSources(cited, permitted);
        String text = Texts.plainText(string(item, TEXT, MAX_PARAGRAPH_CODE_POINTS, false));
        verifyParagraph(text, permitted, references, rules);
        verifyRequiredDetails(text, facts, rules.isAnonymous());
        return new Paragraph(text, references, cited);
    }

    private static void verifyCitedSources(Set<String> cited, Set<String> permitted) {
        if (cited.isEmpty()) {
            throw new InvalidResult("MISSING_CITATION");
        }
        if (!permitted.containsAll(cited)) {
            throw new IllegalStateException(INVALID_CITATION);
        }
    }

    private static void verifyParagraph(
            String text, Set<String> permitted, List<String> references, Rules rules) {
        if (text.isBlank()) {
            throw new InvalidResult("EMPTY_REPORT");
        }
        checkCitations(text, permitted);
        verifyProse(text);
        rules.verifyAnonymity(text, references);
    }

    private static void verifyProse(String text) {
        try {
            DailySummaryPresentation.validateProse(text);
        } catch (IllegalStateException failure) {
            throw new InvalidResult(failure.getMessage());
        }
    }

    private static void verifyRequiredDetails(String text, List<Fact> facts, boolean isAnonymous) {
        for (Fact fact : facts) {
            if (fact.critical() && lacksRequiredDetails(text, fact, isAnonymous)) {
                throw new InvalidResult(REQUIRED_FACT_MISSING, List.of(fact.id()), 0, 1);
            }
        }
    }

    private static boolean lacksRequiredDetails(String text, Fact fact, boolean isAnonymous) {
        boolean isOwnerMissing =
                !isAnonymous && !fact.owner().isEmpty() && !text.contains(fact.owner());
        boolean isDeadlineMissing = !fact.due().isEmpty() && !text.contains(fact.due());
        return isOwnerMissing || isDeadlineMissing;
    }

    private static void verifyLength(String lead, List<Paragraph> paragraphs, int target) {
        if (paragraphs.isEmpty()) {
            throw invalid();
        }
        int length =
                codePoints(lead)
                        + paragraphs.stream()
                                .map(Paragraph::text)
                                .mapToInt(DailySummaryWriting::codePoints)
                                .sum();
        if (length > target) {
            throw new InvalidResult("REPORT_TOO_LONG", List.of(), length, target);
        }
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    record Rules(int target, boolean isAnonymous, Set<String> forbiddenSpeakers) {
        Rules {
            forbiddenSpeakers =
                    forbiddenSpeakers == null ? Set.of() : Set.copyOf(forbiddenSpeakers);
        }

        static Rules forProfile(int target, SummaryProfile profile, Set<String> forbiddenSpeakers) {
            boolean isAnonymous =
                    profile != null
                            && profile.speakerMode() == SummaryProfile.SpeakerMode.ANONYMOUS;
            return new Rules(target, isAnonymous, forbiddenSpeakers);
        }

        void verifyAnonymity(String text, List<String> references) {
            if (!isAnonymous) {
                return;
            }
            for (String speaker : forbiddenSpeakers) {
                if (!speaker.isBlank() && text.contains(speaker)) {
                    throw new InvalidResult("SPEAKER_LEAKED", references, 1, 0);
                }
            }
        }
    }

    private static final class Coverage {
        private final List<Fact> inputs;
        private final Map<String, Fact> byId = new LinkedHashMap<>();
        private final Set<String> seen = new HashSet<>();

        Coverage(List<Fact> inputs) {
            this.inputs = List.copyOf(inputs);
            for (Fact fact : inputs) {
                byId.put(fact.id(), fact);
            }
        }

        List<Fact> resolve(List<String> ids) {
            var facts = new ArrayList<Fact>();
            for (String id : ids) {
                Fact fact = byId.get(id);
                if (fact == null) {
                    throw new IllegalStateException(INVALID_CITATION);
                }
                if (!seen.add(id)) {
                    throw new InvalidResult("DUPLICATE_FACTS", List.of(id), 2, 1);
                }
                facts.add(fact);
            }
            return facts;
        }

        void verifyCoverage() {
            var missing = inputs.stream().map(Fact::id).filter(id -> !seen.contains(id)).toList();
            if (!missing.isEmpty()) {
                throw new InvalidResult(REQUIRED_FACT_MISSING, missing, seen.size(), inputs.size());
            }
        }
    }
}
