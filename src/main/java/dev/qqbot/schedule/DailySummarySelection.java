/**
 * [INPUT]: 接收选材阶段的原始 JSON 与全量已编辑事实。
 * [OUTPUT]: 返回选中或省略的事实及其理由。
 * [POS]: 日报选材输出边界。每个事实须有且只有一条去向。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static dev.qqbot.schedule.DailySummaryFacts.invalid;
import static dev.qqbot.schedule.DailySummaryJson.array;
import static dev.qqbot.schedule.DailySummaryJson.fields;
import static dev.qqbot.schedule.DailySummaryJson.parse;
import static dev.qqbot.schedule.DailySummaryJson.string;

import com.fasterxml.jackson.databind.JsonNode;
import dev.qqbot.schedule.DailySummaryFacts.Choice;
import dev.qqbot.schedule.DailySummaryFacts.Fact;
import dev.qqbot.schedule.DailySummaryFacts.InvalidResult;
import dev.qqbot.schedule.DailySummaryFacts.Value;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DailySummarySelection {
    private static final int MAX_ID_LENGTH = 30;
    private static final int MAX_DETAIL_LENGTH = 10;
    private static final int MAX_REASON_LENGTH = 30;
    private static final String ID = "id";
    private static final String DETAIL = "detail";
    private static final String REASON = "reason";
    private static final String SELECTED = "selected";
    private static final String OMITTED = "omitted";
    private static final Set<String> DETAIL_LEVELS = Set.of("FULL", "BRIEF");
    private static final Set<String> OMISSION_REASONS =
            Set.of("LOW_VALUE", "OFF_TOPIC", "BACKGROUND");

    private DailySummarySelection() {}

    static List<Choice> readSelection(String raw, List<Fact> inputs) {
        var root = parse(raw, DailySummaryFacts.sources(inputs));
        fields(root, SELECTED, OMITTED);
        var coverage = new Coverage(inputs);
        var choices = new ArrayList<Choice>();
        for (JsonNode entry : array(root, SELECTED)) {
            choices.add(readSelected(entry, coverage));
        }
        for (JsonNode entry : array(root, OMITTED)) {
            choices.add(readOmitted(entry, coverage));
        }
        coverage.verifyCoverage();
        return List.copyOf(choices);
    }

    private static Choice readSelected(JsonNode entry, Coverage coverage) {
        fields(entry, ID, DETAIL);
        Fact fact = coverage.review(string(entry, ID, MAX_ID_LENGTH, false));
        if (fact.value() == Value.OMIT) {
            throw new InvalidResult("SELECTION_TOO_BROAD");
        }
        String detail = string(entry, DETAIL, MAX_DETAIL_LENGTH, false);
        if (!DETAIL_LEVELS.contains(detail)) {
            throw invalid();
        }
        return new Choice(fact.id(), detail, "");
    }

    private static Choice readOmitted(JsonNode entry, Coverage coverage) {
        fields(entry, ID, REASON);
        Fact fact = coverage.review(string(entry, ID, MAX_ID_LENGTH, false));
        if (fact.critical()) {
            throw new InvalidResult("REQUIRED_FACT_MISSING", List.of(fact.id()), 0, 1);
        }
        String reason = string(entry, REASON, MAX_REASON_LENGTH, false);
        if (!OMISSION_REASONS.contains(reason)) {
            throw invalid();
        }
        return new Choice(fact.id(), "", reason);
    }

    private static final class Coverage {
        private final Map<String, Fact> byId = new LinkedHashMap<>();
        private final Set<String> seen = new HashSet<>();
        private final int expected;

        Coverage(List<Fact> inputs) {
            this.expected = inputs.size();
            for (Fact fact : inputs) {
                byId.put(fact.id(), fact);
            }
        }

        Fact review(String id) {
            Fact fact = byId.get(id);
            if (fact == null) {
                throw new IllegalStateException("INVALID_CITATION");
            }
            if (!seen.add(id)) {
                throw invalid();
            }
            return fact;
        }

        void verifyCoverage() {
            if (seen.size() != expected) {
                throw invalid();
            }
        }
    }
}
