/**
 * [INPUT]: 接收日报步骤校验器、冻结来源和模型响应。
 * [OUTPUT]: 提供当前事实、选材、稿件和审阅快照。
 * [POS]: 持有一次尝试的内容状态；步骤调度与重试留在 DailySummaryWorkflow。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static java.util.function.Function.identity;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toUnmodifiableMap;

import dev.qqbot.schedule.DailySummaryFacts.*;
import java.util.*;
import java.util.stream.Collectors;

final class DailySummaryMaterial {
    private static final String NON_SUBSTANTIVE = "NON_SUBSTANTIVE";
    private static final String REQUIRED_FACT_MISSING = "SUMMARY_REQUIRED_FACT_MISSING";

    private final SummaryProfile profile;
    private final Map<String, Disposition> dispositions = new LinkedHashMap<>();
    private final Map<String, Fact> facts = new LinkedHashMap<>();
    private final Map<String, Fact> allFacts = new LinkedHashMap<>();
    private final List<Revision> revisions = new ArrayList<>();
    private final Map<String, Choice> editorialChoices = new LinkedHashMap<>();
    private final Map<String, Choice> choices = new LinkedHashMap<>();
    private final List<Paragraph> paragraphs = new ArrayList<>();
    private Map<String, Choice> previousChoices = Map.of();
    private String lead = "";
    private int nextFact = 1;
    private int factsVersion;
    private int selectedFactsVersion = -1;

    DailySummaryMaterial(SummaryProfile profile) {
        this.profile = profile;
    }

    void acceptRead(String raw, List<SourceUnit> batch) {
        List<SourceUnit> sources = batch.stream().filter(DailySummaryMaterial::hasText).toList();
        ReadResult result = DailySummaryReading.read(raw, sources, nextFact);
        List<String> ids = batch.stream().map(SourceUnit::id).toList();
        List<String> placeholders =
                batch.stream().filter(unit -> !hasText(unit)).map(SourceUnit::id).toList();
        requireUnread(ids);
        // SIDE EFFECT: 全批通过校验后才提交事实和处置记录。
        addFacts(result.facts());
        for (Disposition disposition : result.dispositions()) {
            dispositions.put(disposition.unit(), disposition);
        }
        addPlaceholders(placeholders);
        factsChanged();
    }

    private static boolean hasText(SourceUnit unit) {
        return !unit.text().isEmpty();
    }

    void acceptPlaceholders(List<String> ids) {
        requireUnread(ids);
        // SIDE EFFECT: 全批占位通过校验后才记录已读。
        addPlaceholders(ids);
        factsChanged();
    }

    private void requireUnread(List<String> ids) {
        if (ids.stream().anyMatch(dispositions::containsKey)) {
            throw DailySummaryWorkflow.invariant();
        }
    }

    private void addPlaceholders(List<String> ids) {
        for (String id : ids) {
            dispositions.put(id, new Disposition(id, List.of(), NON_SUBSTANTIVE));
        }
    }

    void acceptEdit(String raw, List<String> ids) {
        var rules = DailySummaryEditing.Rules.forEditing(nextFact, profile);
        Edited edited = DailySummaryEditing.revise(raw, requireFacts(ids), rules);
        // SIDE EFFECT: 同一批修订必须同时替换事实与编辑选择。
        editorialChoices.keySet().removeAll(ids);
        reviseFacts(ids, edited.organized());
        for (Choice choice : edited.choices()) {
            editorialChoices.put(choice.fact(), choice);
        }
        factsChanged();
    }

    void acceptOrganization(String raw, List<String> ids) {
        var rules = new DailySummaryEditing.Rules(nextFact, false, false);
        Organized result = DailySummaryEditing.revise(raw, requireFacts(ids), rules).organized();
        // SIDE EFFECT: 原事实保留在审阅记录中，只有新事实进入后续选材。
        reviseFacts(ids, result);
        if (result.changed()) {
            factsChanged();
        }
    }

    private void reviseFacts(List<String> ids, Organized result) {
        facts.keySet().removeAll(ids);
        addFacts(result.facts());
        revisions.addAll(result.revisions());
    }

    private void addFacts(List<Fact> additions) {
        for (Fact fact : additions) {
            facts.put(fact.id(), fact);
            allFacts.put(fact.id(), fact);
        }
        nextFact += additions.size();
    }

    void acceptSelection(String raw, List<String> ids, int totalUnits) {
        requireReadCoverage(totalUnits);
        List<Choice> result = DailySummarySelection.readSelection(raw, requireFacts(ids));
        validateChoices(result);
        // SIDE EFFECT: 不合格的选材批不得覆盖之前已接受的选择。
        for (Choice choice : result) {
            choices.put(choice.fact(), choice);
        }
        selectedFactsVersion = -1;
        clearDraft();
    }

    private void validateChoices(List<Choice> proposed) {
        if (!previousChoices.isEmpty() && proposed.stream().allMatch(this::isPreviousChoice)) {
            throw new InvalidResult("SELECTION_TOO_BROAD");
        }
        if (proposed.stream().map(Choice::fact).anyMatch(choices::containsKey)) {
            throw DailySummaryWorkflow.invariant();
        }
    }

    private boolean isPreviousChoice(Choice choice) {
        return choice.equals(previousChoices.get(choice.fact()));
    }

    void acceptDraft(String raw, List<String> ids, Writing writing) {
        requireConfirmedSelection();
        var rules =
                DailySummaryWriting.Rules.forProfile(
                        writing.target(), profile, writing.forbiddenSpeakers());
        WriteResult written = DailySummaryWriting.validate(raw, requireFacts(ids), rules);
        boolean isWholeReport = ids.size() == selectedCount() || writing.isFinalEdit();
        if (isWholeReport) {
            DailySummaryReport.validatePreview(written, profile);
        }
        // SIDE EFFECT: 全稿重写通过预览校验后才替换分批稿件。
        if (writing.isFinalEdit()) {
            paragraphs.clear();
        }
        if (isWholeReport) {
            lead = written.lead();
        }
        paragraphs.addAll(written.paragraphs());
    }

    void requireReadCoverage(int totalUnits) {
        if (dispositions.size() != totalUnits) {
            throw DailySummaryWorkflow.invariant();
        }
    }

    void confirmSelection(int totalUnits) {
        requireReadCoverage(totalUnits);
        if (!choices.keySet().equals(facts.keySet())) {
            throw DailySummaryWorkflow.invariant();
        }
        for (Fact fact : facts.values()) {
            if (fact.critical() && !isSelected(choices.get(fact.id()))) {
                throw new IllegalStateException(REQUIRED_FACT_MISSING);
            }
        }
        // SIDE EFFECT: 仅全量选材能够解锁成文步骤。
        selectedFactsVersion = factsVersion;
        previousChoices = Map.of();
    }

    void useEditorialChoices() {
        choices.putAll(editorialChoices);
    }

    void refineSelection() {
        previousChoices = Map.copyOf(choices);
        invalidateSelection();
    }

    private void factsChanged() {
        factsVersion++;
        invalidateSelection();
    }

    private void invalidateSelection() {
        selectedFactsVersion = -1;
        choices.clear();
        clearDraft();
    }

    private void clearDraft() {
        paragraphs.clear();
        lead = "";
    }

    private void requireConfirmedSelection() {
        if (selectedFactsVersion != factsVersion) {
            throw DailySummaryWorkflow.invariant();
        }
    }

    List<String> factIds() {
        return List.copyOf(facts.keySet());
    }

    List<String> editorialCandidates() {
        return facts.values().stream()
                .filter(fact -> fact.value() != Value.OMIT)
                .map(Fact::id)
                .toList();
    }

    List<Fact> requireFacts(List<String> ids) {
        return ids.stream().map(this::requireFact).toList();
    }

    private Fact requireFact(String id) {
        Fact fact = facts.get(id);
        if (fact == null) {
            throw DailySummaryWorkflow.invariant();
        }
        return fact;
    }

    List<String> selectedFactIds() {
        return choices.values().stream()
                .filter(DailySummaryMaterial::isSelected)
                .map(Choice::fact)
                .toList();
    }

    private static boolean isSelected(Choice choice) {
        return choice.omission().isEmpty();
    }

    int selectedCount() {
        return (int) choices.values().stream().filter(DailySummaryMaterial::isSelected).count();
    }

    int processedCount() {
        return dispositions.size();
    }

    int factCount() {
        return facts.size();
    }

    int criticalCount() {
        return (int) facts.values().stream().filter(Fact::critical).count();
    }

    int criticalCovered() {
        return (int) facts.values().stream().filter(Fact::critical).filter(this::isCovered).count();
    }

    private boolean isCovered(Fact fact) {
        Choice choice = choices.get(fact.id());
        return choice != null && isSelected(choice);
    }

    int revisionCount() {
        return revisions.size();
    }

    Map<String, Integer> omissionReasons() {
        return choices.values().stream()
                .map(Choice::omission)
                .filter(not(String::isEmpty))
                .collect(toUnmodifiableMap(identity(), reason -> 1, Integer::sum));
    }

    List<Paragraph> paragraphs() {
        return List.copyOf(paragraphs);
    }

    DailySummaryPrompt.Material promptMaterial(List<String> ids, Collection<SourceUnit> units) {
        return new DailySummaryPrompt.Material(requireFacts(ids), units, choices);
    }

    String renderReport(Map<String, SourceUnit> units) {
        requireReadCoverage(units.size());
        requireConfirmedSelection();
        List<String> selected = selectedFactIds();
        Set<String> allowed =
                units.values().stream().map(SourceUnit::messageId).collect(Collectors.toSet());
        if (!paragraphs.isEmpty()) {
            return DailySummaryReport.renderReport(
                    new WriteResult(lead, paragraphs), selected, allowed);
        }
        DailySummaryReport.validateCoverage(paragraphs, selected);
        String citation = units.get(dispositions.keySet().iterator().next()).messageId();
        return DailySummaryReport.renderSmallTalk(citation, allowed);
    }

    Map<String, Object> serializeAudit() {
        return Map.of(
                "facts", List.copyOf(allFacts.values()),
                "revisions", List.copyOf(revisions),
                "choices", List.copyOf(choices.values()),
                "dispositions", List.copyOf(dispositions.values()),
                "lead", lead,
                "profile", profile);
    }

    record Writing(int target, boolean isFinalEdit, Set<String> forbiddenSpeakers) {}
}
