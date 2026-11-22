/**
 * [INPUT]: 接收带来源引用的日报正文、业务日期与采集覆盖元数据（含 styleMode 与 gaps）。
 * [OUTPUT]: 提供群内展示与本地预览同源的无损分段文本（含场景化标题、空行分段与缺口提示）。
 * [POS]: schedule 模块的唯一展示出口，确保 outbox 与本地预览逐字一致且绝不截尾。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import dev.qqbot.text.Texts;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** The same lossless presentation is used by the outbox and local previews. */
public final class DailySummaryPresentation {
    public static final int MAX_TOTAL = 1200, MAX_PART = 500;
    private static final Pattern CITATIONS = Pattern.compile("\\s*\\[[0-9a-fA-F-]{36}\\]");
    private static final Pattern NOISE =
            Pattern.compile(
                    "(?i)(?:[a-z][a-z0-9+.-]*://|www\\.|(?<![\\w@])(?:[a-z0-9-]+\\.)+(?:com|cn|net|org|io|app|dev|edu|gov|me|xyz|top|gg)(?:\\b|/)|\\[[^]\\n]+\\]\\(|\\[(?:\\d+|[0-9a-f-]{36})\\]|https?[:：]|完整报告.{0,12}管理端)");
    private static final Pattern EMPTY_LINK =
            Pattern.compile("(?m)^\\s*(?:[•*—-]\\s*)?(?:分享链接|分享地址|链接|来源|其他)\\s*[:：]?\\s*$");

    private DailySummaryPresentation() {}

    public static void validateProse(String text) {
        if (NOISE.matcher(text).find() || EMPTY_LINK.matcher(text).find()) {
            throw new IllegalStateException("REPORT_PRESENTATION_INVALID");
        }
    }

    public static List<String> render(String report, LocalDate date, Map<String, Object> coverage) {
        String prose = Texts.plainText(CITATIONS.matcher(report).replaceAll(""));
        if (prose.isBlank()) {
            throw new IllegalStateException("EMPTY_REPORT");
        }
        validateProse(prose);
        Object mode = coverage != null ? coverage.get("styleMode") : null;
        String header =
                "WORK".equals(mode)
                        ? "昨日协作纪要 · " + date
                        : "CASUAL".equals(mode) ? "昨日群聊日报 · " + date : "昨日已采集要点 · " + date;
        if (coverage != null
                && coverage.get("gaps") instanceof Collection<?> gaps
                && !gaps.isEmpty()) {
            header += "\n采集有缺口，仅供参考。";
        }
        String text = header + "\n\n" + prose;
        if (length(text) > MAX_TOTAL) {
            throw new IllegalStateException("REPORT_TOO_LONG");
        }
        // Keep every character. Prefer blank-line paragraph boundaries, then line breaks, then
        // complete sentences.
        var parts = new ArrayList<String>();
        int start = 0;
        while (length(text.substring(start)) > MAX_PART) {
            int end = text.offsetByCodePoints(start, MAX_PART), cut = -1;
            for (int i = start; i + 1 < end; i++) {
                if (text.charAt(i) == '\n' && text.charAt(i + 1) == '\n' && i > start) {
                    cut = i + 2;
                }
            }
            if (cut < 0) {
                for (int i = start; i < end; i++) {
                    if (text.charAt(i) == '\n' && i > start) {
                        cut = i + 1;
                    }
                }
            }
            if (cut < 0) {
                for (int i = start; i < end; i++) {
                    if ("。！？；!?;".indexOf(text.charAt(i)) >= 0) {
                        cut = i + 1;
                    }
                }
            }
            if (cut < 0) {
                throw new IllegalStateException("REPORT_SENTENCE_TOO_LONG");
            }
            parts.add(text.substring(start, cut));
            start = cut;
        }
        if (start < text.length()) {
            parts.add(text.substring(start));
        }
        if (parts.size() > 3) {
            throw new IllegalStateException("REPORT_TOO_MANY_PARTS");
        }
        return List.copyOf(parts);
    }

    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }
}
