/**
 * [INPUT]: 接收已核验段落、选材结果与冻结来源。
 * [OUTPUT]: 预检群内展示，并组装带来源的最终日报。
 * [POS]: 日报的成稿与引用边界，不读取或变更尝试状态。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import dev.qqbot.schedule.DailySummaryFacts.InvalidResult;
import dev.qqbot.schedule.DailySummaryFacts.Paragraph;
import dev.qqbot.schedule.DailySummaryFacts.WriteResult;
import dev.qqbot.text.Texts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class DailySummaryReport {
    static final int MAX_PROSE_CODE_POINTS = 1100;
    private static final int MIN_PARAGRAPHS_FOR_LEAD = 2;
    private static final Pattern CITATION = Pattern.compile("\\[[0-9a-fA-F-]{36}\\]");
    private static final Pattern CITATION_WITH_PREFIX =
            Pattern.compile("\\s*" + CITATION.pattern());

    private DailySummaryReport() {}

    static void validatePreview(WriteResult draft, SummaryProfile profile) {
        var lines = new ArrayList<String>();
        if (hasLead(draft)) {
            lines.add(draft.lead());
        }
        for (Paragraph paragraph : draft.paragraphs()) {
            lines.add(paragraph.text());
        }
        try {
            renderPreview(joinFitting(lines), profile);
        } catch (IllegalStateException failure) {
            throw new InvalidResult(failure.getMessage());
        }
    }

    private static void renderPreview(String report, SummaryProfile profile) {
        // 用稳定日期和缺口标记预留固定群内展示文字，不将它们写入最终日报。
        DailySummaryPresentation.render(
                report,
                LocalDate.of(2000, 1, 1),
                Map.of("gaps", List.of("check"), "styleMode", profile.styleMode().name()));
    }

    static String renderReport(
            WriteResult draft, Collection<String> selected, Set<String> allowed) {
        validateCoverage(draft.paragraphs(), selected);
        if (draft.paragraphs().isEmpty()) {
            throw new IllegalStateException("EMPTY_REPORT");
        }
        return validateReport(joinFitting(citedLines(draft)), allowed);
    }

    static String renderSmallTalk(String firstSource, Set<String> allowed) {
        return validateReport(withCitation("昨日已采集消息以闲聊为主，暂无需要列出的要点。", firstSource), allowed);
    }

    static void validateCoverage(List<Paragraph> paragraphs, Collection<String> selected) {
        Set<String> covered =
                paragraphs.stream()
                        .flatMap(paragraph -> paragraph.facts().stream())
                        .collect(Collectors.toSet());
        if (!covered.containsAll(selected)) {
            throw new IllegalStateException("SUMMARY_REQUIRED_FACT_MISSING");
        }
    }

    private static List<String> citedLines(WriteResult draft) {
        var lines = new ArrayList<String>();
        if (hasLead(draft)) {
            Paragraph first = draft.paragraphs().get(0);
            lines.add(withCitation(draft.lead(), first.sources().iterator().next()));
        }
        for (Paragraph paragraph : draft.paragraphs()) {
            lines.add(renderParagraph(paragraph));
        }
        return lines;
    }

    private static String renderParagraph(Paragraph paragraph) {
        var line = new StringBuilder(paragraph.text());
        for (String source : paragraph.sources()) {
            line.append(citation(source));
        }
        return line.toString();
    }

    private static String withCitation(String text, String source) {
        return text + citation(source);
    }

    private static String citation(String source) {
        return " [" + source + "]";
    }

    private static boolean hasLead(WriteResult draft) {
        return !draft.lead().isBlank() && draft.paragraphs().size() >= MIN_PARAGRAPHS_FOR_LEAD;
    }

    private static String joinFitting(List<String> lines) {
        String spaced = String.join("\n\n", lines);
        return fits(spaced) ? spaced : String.join("\n", lines);
    }

    private static boolean fits(String report) {
        String prose = CITATION_WITH_PREFIX.matcher(report).replaceAll("");
        return prose.codePointCount(0, prose.length()) <= MAX_PROSE_CODE_POINTS;
    }

    private static String validateReport(String report, Set<String> allowed) {
        String cleaned = Texts.plainText(report);
        if (cleaned.isBlank()) {
            throw new IllegalStateException("EMPTY_REPORT");
        }
        DailySummaryJson.checkCitations(cleaned, allowed);
        if (!CITATION.matcher(cleaned).find()) {
            throw new IllegalStateException("MISSING_CITATION");
        }
        if (!fits(cleaned)) {
            throw new IllegalStateException("SUMMARY_WORKFLOW_INVARIANT");
        }
        return cleaned;
    }
}
