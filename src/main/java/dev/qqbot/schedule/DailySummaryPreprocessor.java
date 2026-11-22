/**
 * [INPUT]: 依赖共享消息清洗器、群昵称和日报消息时间。
 * [OUTPUT]: 提供干净文本、静默间隔与匿名模式的昵称禁用表。
 * [POS]: 日报输入预处理；不改动原始消息 ID。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import dev.qqbot.text.MessageSanitizer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public final class DailySummaryPreprocessor {
    private DailySummaryPreprocessor() {}

    public static String cleanText(String raw, Map<String, String> memberNames) {
        return MessageSanitizer.clean(raw, memberNames);
    }

    public static long gapSeconds(String prevTime, String currTime) {
        Instant previous = parseInstant(prevTime), current = parseInstant(currTime);
        return previous == null || current == null
                ? 0
                : Math.max(0, Duration.between(previous, current).getSeconds());
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (Exception ignored) {
            try {
                return java.sql.Timestamp.valueOf(value.strip()).toInstant();
            } catch (Exception invalid) {
                return null;
            }
        }
    }

    private static final Set<String> COMMON_WORDS =
            Set.of("测试", "群友", "大家", "有人", "成员", "管理员", "机器人", "同学", "老师", "今天", "昨天", "明天");

    public static Set<String> forbiddenSpeakerNames(Collection<String> senders) {
        if (senders == null || senders.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String raw : senders) {
            if (raw == null) {
                continue;
            }
            String name = raw.strip();
            if (name.codePointCount(0, name.length()) >= 2 && !COMMON_WORDS.contains(name)) {
                result.add(name);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public static String decodeFaceText(String base64Ext) {
        if (base64Ext == null || base64Ext.isBlank()) {
            return "";
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Ext);
            JsonNode node =
                    DailySummaryJson.MAPPER.readTree(new String(decoded, StandardCharsets.UTF_8));
            return node.path("text").asText("").strip();
        } catch (Exception invalid) {
            return "";
        }
    }
}
