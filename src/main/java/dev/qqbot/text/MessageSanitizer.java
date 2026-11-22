/**
 * [INPUT]: 接收平台原文与群内昵称映射。
 * [OUTPUT]: 还原提及，清除协议占位，缩写分享卡片。
 * [POS]: 日报与画像共用输入清洗；不改变原始存储。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.text;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageSanitizer {
    private static final Pattern MENTION = Pattern.compile("<@!?([A-Za-z0-9_-]+)>");
    private static final Pattern FACE_TAG =
            Pattern.compile("<faceType=\\d+,faceId=\"[^\"]*\",ext=\"[^\"]*\">");
    private static final Pattern ATTACHMENT_TAG =
            Pattern.compile(
                    "<attachmentType=\"[^\"]*\",attachmentIndex=\\d+,description=\"[^\"]*\">");
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern JUMP_URL_LINE = Pattern.compile("(?im)^\\s*jump_url\\s*[:：].*$");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");

    private MessageSanitizer() {}

    public static List<String> mentions(String raw) {
        return raw == null
                ? List.of()
                : MENTION.matcher(raw).results().map(match -> match.group(1)).distinct().toList();
    }

    public static String clean(String raw, Map<String, String> memberNames) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text =
                MENTION.matcher(raw)
                        .replaceAll(
                                match -> {
                                    String name =
                                            memberNames == null
                                                    ? null
                                                    : memberNames.get(match.group(1));
                                    return name == null || name.isBlank()
                                            ? ""
                                            : Matcher.quoteReplacement("@" + name.strip() + " ");
                                });
        text = FACE_TAG.matcher(ATTACHMENT_TAG.matcher(text).replaceAll("")).replaceAll("");
        if (text.contains("[卡片消息]")) {
            text = condenseCard(text);
        } else if (text.contains("http://")
                || text.contains("https://")
                || text.contains("HTTP://")
                || text.contains("HTTPS://")) {
            text = URL.matcher(text).replaceAll("[分享链接]");
        }
        return MULTI_SPACE.matcher(text).replaceAll(" ").strip();
    }

    private static String condenseCard(String text) {
        String cleaned = URL.matcher(JUMP_URL_LINE.matcher(text).replaceAll("")).replaceAll("");
        String tag = "", title = "", summary = "";
        var extra = new ArrayList<String>();
        for (String rawLine : cleaned.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("[卡片消息]")) {
                continue;
            }
            if (line.regionMatches(true, 0, "tag:", 0, 4) || line.startsWith("tag：")) {
                tag = line.substring(4).strip();
            } else if (line.regionMatches(true, 0, "title:", 0, 6) || line.startsWith("title：")) {
                title = line.substring(6).strip();
            } else if (line.startsWith("摘要:") || line.startsWith("摘要：")) {
                summary = line.substring(3).strip();
            } else if (line.regionMatches(true, 0, "desc:", 0, 5)) {
                if (summary.isEmpty()) {
                    summary = line.substring(5).strip();
                }
            } else {
                extra.add(line);
            }
        }
        var result = new StringBuilder(tag.isEmpty() ? "[分享卡片]" : "[分享卡片·" + tag + "]");
        String main = title.isEmpty() ? summary : title;
        if (!main.isEmpty()) {
            result.append(' ').append(main);
        }
        for (String line : extra) {
            result.append(' ').append(line);
        }
        return result.toString().strip();
    }
}
