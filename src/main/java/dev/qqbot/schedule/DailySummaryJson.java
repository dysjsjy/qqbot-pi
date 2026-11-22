/**
 * [INPUT]: 接收日报提示的数据与模型输出的原始 JSON。
 * [OUTPUT]: 编码提示数据，严格解析模型对象与字段，并检查可见引用。
 * [POS]: 日报模型协议的 JSON 边界。读、编、选、写共用字段错误码。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static dev.qqbot.schedule.DailySummaryFacts.invalid;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.schedule.DailySummaryFacts.InvalidResult;
import dev.qqbot.text.Texts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class DailySummaryJson {
    private static final int MAX_RESPONSE_LENGTH = 4_000;
    private static final int MAX_NESTING = 20;
    private static final int MAX_ARRAY_ITEMS = 1_000;
    private static final Pattern CITATION = Pattern.compile("\\[([0-9a-fA-F-]{36})\\]");
    static final ObjectMapper MAPPER = createMapper();

    private DailySummaryJson() {}

    private static ObjectMapper createMapper() {
        var limits =
                StreamReadConstraints.builder()
                        .maxNestingDepth(MAX_NESTING)
                        .maxStringLength(MAX_RESPONSE_LENGTH)
                        .build();
        var factory =
                JsonFactory.builder()
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(limits)
                        .build();
        return new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("SUMMARY_WORKFLOW_INVARIANT", failure);
        }
    }

    static JsonNode parse(String raw, Set<String> allowed) {
        if (raw == null || raw.isBlank()) {
            throw invalid();
        }
        if (raw.length() > MAX_RESPONSE_LENGTH) {
            throw new InvalidResult(
                    "RESULT_TOO_LARGE", List.of(), raw.length(), MAX_RESPONSE_LENGTH);
        }
        JsonNode node = readObject(raw);
        // 意外字段里的可见引用也要受来源边界约束。
        checkCitations(raw, allowed);
        return node;
    }

    private static JsonNode readObject(String raw) {
        try {
            JsonNode object = MAPPER.readTree(raw);
            if (object == null || !object.isObject()) {
                throw invalid();
            }
            return object;
        } catch (JsonProcessingException failure) {
            throw invalid();
        }
    }

    static void fields(JsonNode node, String... names) {
        if (!node.isObject()) {
            throw invalid();
        }
        var permitted = Set.of(names);
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!permitted.contains(fields.next())) {
                throw invalid();
            }
        }
    }

    static List<JsonNode> array(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isArray() || value.size() > MAX_ARRAY_ITEMS) {
            throw invalid();
        }
        var result = new ArrayList<JsonNode>();
        for (JsonNode item : value) {
            result.add(item);
        }
        return result;
    }

    static String string(JsonNode node, String name, int max, boolean allowsEmpty) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        String text = value.asText();
        if (text.codePointCount(0, text.length()) > max || (!allowsEmpty && text.isBlank())) {
            throw invalid();
        }
        return text;
    }

    static String display(JsonNode node, String name, int max, boolean allowsEmpty) {
        String value = Texts.plainText(string(node, name, max, allowsEmpty));
        if (!allowsEmpty && value.isBlank()) {
            throw invalid();
        }
        return value;
    }

    static List<String> strings(JsonNode node, String name) {
        var result = new ArrayList<String>();
        for (JsonNode item : array(node, name)) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw invalid();
            }
            result.add(item.asText());
        }
        if (new HashSet<>(result).size() != result.size()) {
            throw invalid();
        }
        return result;
    }

    static void checkCitations(String text, Set<String> allowed) {
        var matcher = CITATION.matcher(text);
        while (matcher.find()) {
            if (!allowed.contains(matcher.group(1))) {
                throw new IllegalStateException("INVALID_CITATION");
            }
        }
    }
}
