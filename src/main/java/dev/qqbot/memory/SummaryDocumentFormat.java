/**
 * [INPUT]: 接收 v2 摘要正文及本次生成允许引用的 UUID。
 * [OUTPUT]: 校验五节结构、正文引用与完整来源清单。
 * [POS]: 摘要生成与发布之间的纯校验边界，不读写文件。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class SummaryDocumentFormat {
    private static final int MAX_OUTPUT_CODE_POINTS = 12_000;
    private static final List<String> TITLES =
            List.of("## 话题", "## 已明确事项", "## 待办", "## 未决与纠正", "## 来源");
    private static final String BULLET = "- ";
    private static final int SOURCE_SECTION = TITLES.size() - 1;
    private static final Pattern CITATION =
            Pattern.compile(
                    "\\[([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\]");

    private SummaryDocumentFormat() {}

    static void validate(String content, Set<UUID> permitted) {
        Objects.requireNonNull(permitted, "permitted must not be null");
        validateLength(content);
        List<List<String>> sections = parseSections(content);
        validateBody(sections.subList(0, SOURCE_SECTION), permitted);
        validateSources(sections.get(SOURCE_SECTION), permitted);
    }

    private static void validateLength(String content) {
        if (content == null || content.isBlank()) {
            throw invalidStructure();
        }
        if (content.codePointCount(0, content.length()) > MAX_OUTPUT_CODE_POINTS) {
            throw new IllegalStateException("MEMORY_OUTPUT_LIMIT");
        }
    }

    private static List<List<String>> parseSections(String content) {
        List<List<String>> sections = new ArrayList<>();
        for (String original : content.split("\\R")) {
            String line = original.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("## ")) {
                appendSection(sections, line);
                continue;
            }
            if (sections.isEmpty()) {
                throw invalidStructure();
            }
            sections.get(sections.size() - 1).add(line);
        }
        if (sections.size() != TITLES.size()) {
            throw invalidStructure();
        }
        return sections;
    }

    private static void appendSection(List<List<String>> sections, String title) {
        int index = sections.size();
        if (index >= TITLES.size() || !TITLES.get(index).equals(title)) {
            throw invalidStructure();
        }
        sections.add(new ArrayList<>());
    }

    private static void validateBody(List<List<String>> body, Set<UUID> permitted) {
        for (List<String> section : body) {
            if (section.equals(List.of("无"))) {
                continue;
            }
            if (section.isEmpty()) {
                throw invalidStructure();
            }
            for (String bullet : section) {
                validateBodyBullet(bullet, permitted);
            }
        }
    }

    private static void validateBodyBullet(String bullet, Set<UUID> permitted) {
        if (requireBullet(bullet).isBlank()) {
            throw invalidStructure();
        }
        var citations = CITATION.matcher(bullet);
        if (!citations.find()) {
            throw invalidCitation();
        }
        do {
            UUID source = UUID.fromString(citations.group(1));
            if (!permitted.contains(source)) {
                throw invalidCitation();
            }
        } while (citations.find());
    }

    private static void validateSources(List<String> lines, Set<UUID> permitted) {
        var listed = new HashSet<UUID>();
        for (String line : lines) {
            UUID source = parseSource(line);
            if (!permitted.contains(source) || !listed.add(source)) {
                throw invalidCitation();
            }
        }
        if (!listed.equals(permitted)) {
            throw invalidCitation();
        }
    }

    private static UUID parseSource(String line) {
        var citation = CITATION.matcher(requireBullet(line));
        if (!citation.matches()) {
            throw invalidCitation();
        }
        return UUID.fromString(citation.group(1));
    }

    private static String requireBullet(String line) {
        if (!line.startsWith(BULLET)) {
            throw invalidStructure();
        }
        return line.substring(BULLET.length());
    }

    private static IllegalStateException invalidStructure() {
        return new IllegalStateException("MEMORY_INVALID_STRUCTURE");
    }

    private static IllegalStateException invalidCitation() {
        return new IllegalStateException("MEMORY_INVALID_CITATION");
    }
}
