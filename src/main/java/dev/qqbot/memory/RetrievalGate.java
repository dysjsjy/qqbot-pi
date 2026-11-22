/**
 * [INPUT]: 接收查询提示、记忆正文与当前会话的历史。
 * [OUTPUT]: 选择检索模式，筛除噪声与越界引用，裁剪普通群历史。
 * [POS]: 检索及上下文的纯规则，窗口组装委托 DialogueWindows。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RetrievalGate {
    public enum Mode {
        FORCE_MEMORY,
        AUTO_MEMORY,
        SKIP_MEMORY
    }

    public record DialogueMessage(
            UUID id, String senderId, String senderName, String body, Instant occurredAt) {}

    public record DialogueWindow(
            UUID anchorId, List<UUID> sourceIds, String body, Instant firstAt, Instant lastAt) {
        public DialogueWindow {
            sourceIds = List.copyOf(sourceIds);
        }
    }

    private static final Set<String> PURE_NOISE =
            Set.of(
                    "哈哈", "哈哈哈", "哈哈哈哈", "嘿嘿", "呵呵", "嘻嘻", "666", "6666", "+1", "233", "2333",
                    "hhh", "草", "牛", "牛逼", "绝了", "笑死");
    private static final Set<String> SHORT_ACKS =
            Set.of(
                    "好", "好的", "行", "可以", "没问题", "收到", "确实", "嗯", "嗯嗯", "哦", "噢", "ok", "1", "赞",
                    "对", "是的");
    private static final List<String> HISTORY_AND_GROUP_CUES =
            List.of(
                    "昨天", "前天", "上次", "上周", "上个月", "刚才", "之前", "以前", "那天", "几号", "几点", "周几", "星期",
                    "周一", "周二", "周三", "周四", "周五", "周六", "周日", "何时", "最近", "群", "大家", "谁", "楼上",
                    "上面", "刚才说", "怎么说", "说的", "提过", "聊过", "讨论", "记录", "历史", "安排", "决定", "定在", "约定",
                    "聚餐", "例会", "会议", "发布", "上线", "版本", "方案", "进度", "待办", "负责", "推荐", "报错", "日志",
                    "原因", "老地方", "记得", "哪天", "什么时候");
    private static final Set<String> CASUAL_GREETINGS =
            Set.of(
                    "你好", "您好", "早", "早安", "早上好", "中午好", "下午好", "晚上好", "晚安", "在吗", "在不在", "测试",
                    "hi", "hello", "hey", "ping", "谢谢", "多谢", "哈哈", "哈哈哈", "666");
    private static final List<String> PURE_UTILITY_PREFIXES =
            List.of(
                    "用java", "用python", "用js", "用ts", "用go", "用c++", "用rust", "写个", "写一个", "帮我写",
                    "实现一个", "二分查找", "冒泡排序", "快速排序", "正则表达式", "翻译成", "怎么翻译", "1+1", "解释一下什么是");
    private static final List<String> POISON_PATTERNS =
            List.of(
                    "忽略以上",
                    "忽略之前",
                    "忽略上述",
                    "无视规则",
                    "系统指令",
                    "系统提示",
                    "system prompt",
                    "ignore previous",
                    "作为ai",
                    "每次回复必须",
                    "所有回复必须",
                    "必须输出",
                    "泄露",
                    "密钥",
                    "密码",
                    "api_key",
                    "apikey",
                    "执行命令",
                    "越权");
    private static final Pattern UUID_REF =
            Pattern.compile(
                    "\\[([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\]");
    private static final int MIN_AUTO_MEMORY_CODE_POINTS = 200;
    private static final int MAX_AUTO_MEMORY_CODE_POINTS = 1_200;
    private static final int MIN_RECENT_GROUP_EVENTS = 5;

    private RetrievalGate() {}

    /** 生成大小写与标点无关的重复检测键。空输入生成空键。 */
    public static String normalizeNoiseKey(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.strip().toLowerCase(Locale.ROOT);
        StringBuilder key = new StringBuilder();
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint) || codePoint == '+') {
                key.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return key.toString();
    }

    /** 判断消息是否只有固定噪声、重复语气符号或空白。 */
    public static boolean isAlwaysNoise(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String norm = normalizeNoiseKey(text);
        if (norm.isEmpty()) {
            return true;
        }
        if (PURE_NOISE.contains(norm)) {
            return true;
        }
        return norm.chars()
                .allMatch(
                        c -> c == '哈' || c == '6' || c == '2' || c == '3' || c == 'h' || c == '啊');
    }

    /** 识别需要临近上下文才能保留的短确认。 */
    public static boolean isShortAck(String text) {
        if (text == null) {
            return false;
        }
        String norm = normalizeNoiseKey(text);
        return !norm.isEmpty()
                && norm.codePointCount(0, norm.length()) <= 3
                && SHORT_ACKS.contains(norm);
    }

    /** 根据显式查证、历史线索和通用问句选择检索模式。 */
    public static Mode classify(String kind, String prompt, String groupMemoryContext) {
        if ("MEMORY".equalsIgnoreCase(kind)) {
            return Mode.FORCE_MEMORY;
        }
        if (prompt == null || prompt.isBlank()) {
            return Mode.SKIP_MEMORY;
        }
        String cleaned = prompt.replaceFirst("(?i)^@\\S+\\s*", "").strip();
        String lower = cleaned.toLowerCase(Locale.ROOT);
        String norm = normalizeNoiseKey(cleaned);
        if (norm.isEmpty() || CASUAL_GREETINGS.contains(norm) || CASUAL_GREETINGS.contains(lower)) {
            return Mode.SKIP_MEMORY;
        }
        if (HISTORY_AND_GROUP_CUES.stream().anyMatch(lower::contains)
                || refersToMemory(cleaned, groupMemoryContext)) {
            return Mode.AUTO_MEMORY;
        }
        if (PURE_UTILITY_PREFIXES.stream().anyMatch(lower::contains)
                || cleaned.codePointCount(0, cleaned.length()) <= 4) {
            return Mode.SKIP_MEMORY;
        }
        return Mode.AUTO_MEMORY;
    }

    private static boolean refersToMemory(String question, String memory) {
        if (memory == null || memory.isBlank()) {
            return false;
        }
        String lower = memory.toLowerCase(Locale.ROOT);
        return SearchText.queryTerms(question).stream()
                .anyMatch(term -> term.length() >= 2 && lower.contains(term));
    }

    /** 根据条数、码点数和消息间隔切分有序对话。 */
    public static List<DialogueWindow> buildDialogueWindows(
            List<DialogueMessage> messages, int maxMessages, int maxChars, long sessionGapSeconds) {
        return DialogueWindows.build(
                messages, new DialogueWindows.Limits(maxMessages, maxChars, sessionGapSeconds));
    }

    /** 检查记忆行是否包含禁止注入上下文的指令或敏感词。 */
    public static boolean isPoisonedLine(String line) {
        if (line == null) {
            return true;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        for (String pattern : POISON_PATTERNS) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    /** 按来源约束合并记忆正文，只保留上限内的完整行。 */
    public static String distillSafeAutoMemory(
            String existingAutoMemory,
            String candidateSummary,
            Set<UUID> permittedSources,
            int maxCodepoints) {
        int cap =
                Math.max(
                        MIN_AUTO_MEMORY_CODE_POINTS,
                        Math.min(MAX_AUTO_MEMORY_CODE_POINTS, maxCodepoints));
        Set<UUID> allowed = permittedSources == null ? Set.of() : new HashSet<>(permittedSources);
        var validLines = new LinkedHashSet<String>(distillLines(existingAutoMemory, allowed));
        validLines.addAll(distillLines(candidateSummary, allowed));
        return fitLines(validLines, cap);
    }

    private static List<String> distillLines(String block, Set<UUID> allowed) {
        if (block == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : block.split("\\R")) {
            String line = rawLine.strip();
            if (isEligibleMemoryLine(line) && hasPermittedReferences(line, allowed)) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static boolean isEligibleMemoryLine(String line) {
        return !line.isEmpty()
                && !line.startsWith("#")
                && !line.equals("无")
                && !isPoisonedLine(line);
    }

    private static boolean hasPermittedReferences(String line, Set<UUID> allowed) {
        Matcher references = UUID_REF.matcher(line);
        boolean hasReference = false;
        while (references.find()) {
            UUID reference = UUID.fromString(references.group(1));
            if (!allowed.isEmpty() && !allowed.contains(reference)) {
                return false;
            }
            hasReference = true;
        }
        return hasReference;
    }

    private static String fitLines(Set<String> lines, int limit) {
        var selected = new ArrayList<String>();
        int used = 0;
        for (String line : lines) {
            int cost = (selected.isEmpty() ? 0 : 1) + line.codePointCount(0, line.length());
            if (used + cost > limit) {
                break;
            }
            selected.add(line);
            used += cost;
        }
        return String.join("\n", selected);
    }

    /** 保留显式引用和本会话代际，限制普通群历史条数。 */
    public static List<HistoryEvent> microCompactHistory(
            List<HistoryEvent> history,
            UUID conversationId,
            long generation,
            int maxRecentGroupEvents) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int groupCap = Math.max(MIN_RECENT_GROUP_EVENTS, maxRecentGroupEvents);
        List<HistoryEvent> sorted =
                history.stream()
                        .sorted(
                                Comparator.comparingLong(HistoryEvent::seq)
                                        .thenComparing(HistoryEvent::sourceKey))
                        .toList();
        var kept = selectHistoryKeys(sorted, conversationId, generation, groupCap);
        return sorted.stream().filter(event -> kept.contains(event.sourceKey())).toList();
    }

    private static Set<String> selectHistoryKeys(
            List<HistoryEvent> sorted, UUID conversationId, long generation, int groupCap) {
        Set<String> kept = new HashSet<>();
        int groupCount = 0;
        for (int index = sorted.size() - 1; index >= 0; index--) {
            HistoryEvent event = sorted.get(index);
            if (event.explicitlyReferenced() || isOwnTurn(event, conversationId, generation)) {
                kept.add(event.sourceKey());
            } else if (!isAlwaysNoise(event.body()) && groupCount < groupCap) {
                kept.add(event.sourceKey());
                groupCount++;
            }
        }
        return kept;
    }

    private static boolean isOwnTurn(HistoryEvent event, UUID conversationId, long generation) {
        return conversationId != null
                && conversationId.equals(event.conversationId())
                && event.generation() != null
                && event.generation() == generation;
    }
}
