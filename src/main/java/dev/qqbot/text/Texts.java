package dev.qqbot.text;

import java.util.*;
import java.util.regex.Pattern;

public final class Texts {
    private static final Pattern PRIVATE_BLOCK =
            Pattern.compile("(?is)<(think|thinking|analysis)\\b[^>]*>.*?(?:</\\1\\s*>|$)");
    private static final Pattern LOG_LINE =
            Pattern.compile(
                    "^(?:\\d{4}-\\d{2}-\\d{2}[T ][\\d:.+Z-]+\\s+(?:TRACE|DEBUG|INFO|WARN|ERROR)\\b.*|\\[(?:TRACE|DEBUG|INFO|WARN|ERROR)\\]\\s+.*|(?:AGENT_START|AGENT_SUCCEEDED|SUMMARY_START|SUMMARY_GENERATED|MESSAGE_SENT)\\s+(?:run|outbox)=.*|\\s*at [\\w.$]+\\([^)]*\\)\\s*)$");
    private static final Pattern RPC_LINE =
            Pattern.compile(
                    "^\\s*\\{\\s*\"type\"\\s*:\\s*\"(?:response|message_update|message_end|agent_start|agent_end|agent_settled|tool_execution_start|tool_execution_end)\".*$");
    private static final Pattern INLINE_CODE = Pattern.compile("(`+)(.+?)\\1(?!`)");

    private Texts() {}

    public static String plainText(String text) {
        String clean = PRIVATE_BLOCK.matcher(text.replace("\r\n", "\n")).replaceAll("");
        List<String> lines = new ArrayList<>();
        String fence = null;
        for (String line : clean.split("\n", -1)) {
            String stripped = line.strip();
            if (LOG_LINE.matcher(stripped).matches() || RPC_LINE.matcher(stripped).matches()) {
                continue;
            }
            if (fence != null) {
                if (stripped.matches(
                        Pattern.quote(fence.charAt(0) + "") + "{" + fence.length() + ",}\\s*")) {
                    fence = null;
                } else {
                    lines.add(line);
                }
                continue;
            }
            if (stripped.matches("^(?:`{3,}|~{3,}).*")) {
                int length = 0;
                while (length < stripped.length()
                        && stripped.charAt(length) == stripped.charAt(0)) {
                    length++;
                }
                fence = stripped.substring(0, length);
                continue;
            }
            if (stripped.matches("(?:\\|?\\s*:?-{3,}:?\\s*)+\\|?")) {
                continue;
            }
            line = line.replaceFirst("^\\s{0,3}#{1,6}\\s+", "").replaceFirst("\\s+#+\\s*$", "");
            line = line.replaceFirst("^\\s{0,3}>\\s?", "").replaceFirst("^\\s*[-*+]\\s+", "• ");
            if (stripped.startsWith("|") && stripped.endsWith("|")) {
                line =
                        stripped.substring(1, stripped.length() - 1)
                                .replaceAll("\\s*\\|\\s*", " · ")
                                .strip();
            }
            var code = INLINE_CODE.matcher(line);
            StringBuilder formatted = new StringBuilder();
            int end = 0;
            while (code.find()) {
                formatted
                        .append(inlineText(line.substring(end, code.start())))
                        .append(code.group(2));
                end = code.end();
            }
            formatted.append(inlineText(line.substring(end)));
            String display = formatted.toString().stripTrailing();
            if (!display.isBlank() || lines.isEmpty() || !lines.get(lines.size() - 1).isBlank()) {
                lines.add(display);
            }
        }
        int start = 0, end = lines.size();
        while (start < end && lines.get(start).isBlank()) {
            start++;
        }
        while (end > start && lines.get(end - 1).isBlank()) {
            end--;
        }
        return String.join("\n", lines.subList(start, end));
    }

    private static String inlineText(String text) {
        return text.replaceAll("!?\\[([^]\\n]+)\\]\\((https?://[^)\\s]+)\\)", "$1 ($2)")
                .replaceAll("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*", "$1")
                .replaceAll("__(?=\\S)(.+?)(?<=\\S)__", "$1")
                .replaceAll("(?<![\\w*])\\*(?=\\S)(.+?)(?<=\\S)\\*(?![\\w*])", "$1")
                .replaceAll("(?<![\\w_])_(?=\\S)(.+?)(?<=\\S)_(?![\\w_])", "$1")
                .replaceAll("~~(.+?)~~", "$1");
    }

    public static String summaryDelivery(String report) {
        return plainText(report.replaceAll("\\s*\\[[0-9a-fA-F-]{36}\\]", "") + "\n完整报告与来源可在管理端查看。");
    }

    public static List<String> chunks(String text, int maxCodepoints) {
        if (maxCodepoints < 1) {
            throw new IllegalArgumentException("Invalid limit");
        }
        List<String> result = new ArrayList<>();
        int start = 0, remaining = text.codePointCount(0, text.length());
        while (remaining > 0) {
            int count = Math.min(maxCodepoints, remaining);
            int end = text.offsetByCodePoints(start, count);
            result.add(text.substring(start, end));
            start = end;
            remaining -= count;
        }
        return result;
    }

    public static List<String> delivery(String text) {
        return splitDelivery(plainText(text));
    }

    public static List<String> splitDelivery(String text) {
        int length = text.codePointCount(0, text.length());
        if (length <= 1500) {
            return chunks(text, 500);
        }
        var result =
                new ArrayList<>(chunks(text.substring(0, text.offsetByCodePoints(0, 1500)), 500));
        String suffix = "\n（展示已截断，完整报告请在管理端查看）";
        result.set(
                2,
                chunks(result.get(2), 500 - suffix.codePointCount(0, suffix.length())).get(0)
                        + suffix);
        return result;
    }

    public static String command(String text) {
        String stripped = text.strip();
        return stripped.startsWith("/") ? stripped.split("\\s+", 2)[0] : "";
    }
}
