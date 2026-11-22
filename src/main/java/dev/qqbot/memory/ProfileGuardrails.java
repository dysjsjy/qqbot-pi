/**
 * [INPUT]: 接收模型 JSON、本批本人 UUID 与有效观测 ID。
 * [OUTPUT]: 校验补丁、重铸引用和可保存的纯文本。
 * [POS]: 所有画像内容先穿过此边界；模型不能声明证据归属。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import dev.qqbot.text.MessageSanitizer;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ProfileGuardrails {
    static final int MAX_OBSERVATIONS = 24;
    static final int MAX_CARD = 220;
    private static final Pattern SENSITIVE =
            Pattern.compile(
                    "(?iu)"
                            + "(?:[\\w.%+-]+@[\\w.-]+\\.[a-z]{2,}|(?:\\p{Nd}[\\s()\\-.]*){7,}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"
                            + "|(?:路|街|巷|弄)[^，。]{0,12}[0-9一二三四五六七八九十]+\\s*(?:号|栋|室)|真名|实名|本名|我叫|姓名|身份证|户籍|家庭地址|住址|门牌|学号|手机号|电话号|电子邮件|银行卡"
                            + "|银行账号|余额|工资|收入|财务|资产|信用卡|诊断|病历|疾病|抑郁|处方|药物|医疗|病史|政治|宗教|党派|投票|性取向|民族|种族"
                            + "|弱智|智障|傻[逼比]|蠢货|蠢|矫情|废物|暴躁|贱人|人渣|脑残|idiot|moron|doxx|passport|social security|phone number|home address|medical record|bank account)");
    private static final Pattern INSTRUCTION =
            Pattern.compile(
                    "(?iu)(忽略.{0,12}(指令|提示|规则)|必须回复|务必回复|以后.{0,8}回复|命令你|管理员权限|系统提示|开发者指令|system prompt|ignore.{0,24}(previous|instruction)|you must|<\\/?(?:system|assistant|user)|\\[INST])");
    private static final Pattern FORBIDDEN_CHAR = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\t\\r\\n]]");
    private final ObjectReader reader;

    public ProfileGuardrails(ObjectMapper json) {
        this.reader =
                json.readerFor(JsonNode.class)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    enum Action {
        ADD,
        REINFORCE,
        UPDATE,
        CONTRADICT,
        NOOP
    }

    enum Category {
        IDENTITY_ROLE,
        INTEREST_TOPIC,
        EXPERTISE,
        SPEAKING_STYLE,
        SOCIAL_PREF,
        RUNNING_GAG
    }

    record Op(
            Action action,
            UUID targetId,
            Category category,
            String statement,
            double confidence,
            List<UUID> sourceIds) {}

    List<Op> operations(String output, Set<UUID> sources, Set<UUID> targets) {
        JsonNode root = parse(output);
        fields(root, Set.of("ops"));
        JsonNode ops = root.path("ops");
        if (!ops.isArray() || ops.size() > MAX_OBSERVATIONS) {
            throw invalid();
        }
        List<Op> result = new ArrayList<>();
        var touched = new HashSet<UUID>();
        for (JsonNode value : ops) {
            if (!value.isObject()) {
                throw invalid();
            }
            Action action = enumValue(Action.class, value.path("action"));
            if (action == Action.NOOP) {
                fields(value, Set.of("action"));
                continue;
            }
            var keys =
                    new HashSet<>(
                            Set.of(
                                    "action",
                                    "category",
                                    "statement",
                                    "confidence",
                                    "sourceMessageIds"));
            if (action != Action.ADD || value.has("targetId")) {
                keys.add("targetId");
            }
            fields(value, keys);
            UUID target = null;
            if (action != Action.ADD) {
                target = uuid(value.path("targetId"));
                if (!targets.contains(target) || !touched.add(target)) {
                    throw invalid();
                }
            } else if (value.has("targetId") && !value.path("targetId").isNull()) {
                throw invalid();
            }
            double confidence = value.path("confidence").doubleValue();
            if (!value.path("confidence").isNumber()
                    || !Double.isFinite(confidence)
                    || confidence < 0
                    || confidence > 1) {
                throw invalid();
            }
            String statement = cleanField(string(value.path("statement")), 80);
            if (statement.isEmpty()) {
                throw invalid();
            }
            var ids = ids(value.path("sourceMessageIds"), sources, 8);
            if (ids.isEmpty()) {
                throw invalid();
            }
            result.add(
                    new Op(
                            action,
                            target,
                            enumValue(Category.class, value.path("category")),
                            statement,
                            confidence,
                            ids));
        }
        return List.copyOf(result);
    }

    List<UUID> reflection(String output, Set<UUID> confirmed) {
        JsonNode root = parse(output);
        fields(root, Set.of("observationIds"));
        return ids(root.path("observationIds"), confirmed, 6);
    }

    static String input(String raw) {
        String cleaned = MessageSanitizer.clean(raw, Map.of());
        if (!safe(cleaned)
                || RetrievalGate.isAlwaysNoise(cleaned)
                || RetrievalGate.isShortAck(cleaned)
                || cleaned.startsWith("/")) {
            return "";
        }
        return cut(cleaned, 300);
    }

    static String cleanField(String value, int maximum) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip();
        if (!safe(normalized) || normalized.codePointCount(0, normalized.length()) > maximum) {
            throw invalid();
        }
        return normalized.replaceAll("\\s+", " ");
    }

    static String cut(String value, int maximum) {
        return value.codePointCount(0, value.length()) <= maximum
                ? value
                : value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    private static boolean safe(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return !FORBIDDEN_CHAR.matcher(normalized).find()
                && !RetrievalGate.isPoisonedLine(normalized)
                && !INSTRUCTION.matcher(normalized).find()
                && !SENSITIVE.matcher(normalized).find();
    }

    private JsonNode parse(String text) {
        if (text == null || text.length() > 18000) {
            throw invalid();
        }
        try {
            return reader.readValue(text);
        } catch (java.io.IOException failure) {
            throw invalid();
        }
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (!node.isObject()
                || node.size() != expected.size()
                || expected.stream().anyMatch(field -> !node.has(field))) {
            throw invalid();
        }
    }

    private static List<UUID> ids(JsonNode node, Set<UUID> allowed, int max) {
        if (!node.isArray() || node.size() > max) {
            throw invalid();
        }
        var values = new LinkedHashSet<UUID>();
        for (var item : node) {
            UUID id = uuid(item);
            if (!allowed.contains(id) || !values.add(id)) {
                throw invalid();
            }
        }
        return List.copyOf(values);
    }

    private static UUID uuid(JsonNode node) {
        String value = string(node);
        UUID id;
        try {
            id = UUID.fromString(value);
        } catch (IllegalArgumentException rejected) {
            throw invalid();
        }
        if (!id.toString().equals(value)) {
            throw invalid();
        }
        return id;
    }

    private static String string(JsonNode node) {
        if (!node.isTextual()) {
            throw invalid();
        }
        return node.textValue();
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, JsonNode node) {
        try {
            return Enum.valueOf(type, string(node));
        } catch (IllegalArgumentException invalid) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_PROFILE_OUTPUT");
    }
}
