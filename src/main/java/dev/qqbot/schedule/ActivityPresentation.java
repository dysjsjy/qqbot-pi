/**
 * [INPUT]: 接收已采集群成员的昵称和冻结排名。
 * [OUTPUT]: 输出可展示的昵称与符合平台长度的文本分段。
 * [POS]: 后台与群发共享脱敏昵称；群发不读取原始消息或成员 ID。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

final class ActivityPresentation {
    private static final Pattern UNSAFE =
            Pattern.compile(
                    "<[^>]*>|(?i:[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}|(?:[a-z][a-z0-9+.-]*://|www\\.)[^\\s]+|[a-z0-9][a-z0-9.-]+\\.[a-z]{2,}(?:/[^\\s]*)?)|[@<>\\p{C}\\p{Zl}\\p{Zp}]");
    private static final int NICKNAME_LIMIT = 32;
    private static final int PART_LIMIT = 500;

    record Rank(int rank, String nickname, long messageCount) {}

    private ActivityPresentation() {}

    static String nickname(String value, String senderId, int rank) {
        if (value == null) {
            return "成员 #" + rank;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        String normalizedId = Normalizer.normalize(senderId, Normalizer.Form.NFKC);
        if (normalized.equals(normalizedId)) {
            return "成员 #" + rank;
        }
        String clean = UNSAFE.matcher(normalized).replaceAll(" ");
        if (normalizedId.length() >= 8) {
            clean = clean.replace(normalizedId, " ");
        }
        clean = clean.strip().replaceAll("\\s+", " ");
        if (clean.isEmpty()) {
            return "成员 #" + rank;
        }
        return clean.substring(
                0, clean.offsetByCodePoints(0, Math.min(NICKNAME_LIMIT, length(clean))));
    }

    static List<String> render(LocalDate date, List<Rank> ranking, boolean partial) {
        var parts = new ArrayList<String>();
        var part = new StringBuilder("昨日活跃榜 · ").append(date).append("\n仅统计已采集的群消息");
        if (partial) {
            part.append("（部分时段采集有缺口）");
        }
        for (var rank : ranking) {
            String line = rank.rank() + ". " + rank.nickname() + " · " + rank.messageCount() + " 条";
            if (length(part) + 1 + length(line) > PART_LIMIT) {
                parts.add(part.toString());
                part.setLength(0);
            }
            if (!part.isEmpty()) {
                part.append('\n');
            }
            part.append(line);
        }
        parts.add(part.toString());
        return List.copyOf(parts);
    }

    private static int length(CharSequence text) {
        return Character.codePointCount(text, 0, text.length());
    }
}
