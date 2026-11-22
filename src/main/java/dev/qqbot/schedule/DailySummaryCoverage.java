/**
 * [INPUT]: 接收当前采集覆盖、场景配置或历史快照的 JSON。
 * [OUTPUT]: 提供报告策略、缺口标记和兼容既有字段的覆盖 JSON。
 * [POS]: 日报覆盖元数据的 SQLite 与展示序列化边界。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.qqbot.persistence.Db;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record DailySummaryCoverage(
        String json, SummaryProfile profile, int messages, boolean isIncomplete) {
    static final String COLLECTION_NOT_ENABLED = "COLLECTION_NOT_ENABLED";
    private static final String MESSAGES = "messages";
    private static final String GAPS = "gaps";
    private static final String REASON = "reason";
    private static final String QQ_OFFICIAL = "QQ_OFFICIAL";
    private static final String QQ_NOTICE =
            "仅汇总开放平台实际推送且已保存的消息；是否包含未 @ 消息取决于接收所有消息的权限与订阅，不能据此认定全群记录完整。";
    private static final String LATE_POLICY = "快照之后到达的事件不纳入本次分析";

    static DailySummaryCoverage freeze(
            Db db, Capture capture, SummaryProfile profile, boolean isOfficialQq) {
        Counts counts = capture.counts();
        Window window = capture.window();
        var metadata =
                new FrozenCoverage(
                        isOfficialQq ? QQ_OFFICIAL : null,
                        isOfficialQq ? QQ_NOTICE : null,
                        counts.messages(),
                        counts.nonText(),
                        capture.gaps().stream().map(Gap::freeze).toList(),
                        window.from(),
                        window.to(),
                        window.snapshotAt(),
                        LATE_POLICY,
                        profile.styleMode().name(),
                        profile.speakerMode().name());
        return new DailySummaryCoverage(
                db.json(metadata), profile, counts.messages(), !capture.gaps().isEmpty());
    }

    static DailySummaryCoverage reopen(Db db, String json, SummaryProfile current, Reopen rules) {
        var fields = new LinkedHashMap<String, Object>();
        if (rules.isOfficialQq()) {
            fields.put("sourceMode", QQ_OFFICIAL);
            fields.put("scopeNotice", QQ_NOTICE);
        }
        fields.putAll(decode(db, json));
        if (rules.removesMissingCollection() && fields.get(GAPS) instanceof List<?> gaps) {
            fields.put(GAPS, gaps.stream().filter(DailySummaryCoverage::isRecordedGap).toList());
        }
        SummaryProfile profile = current == null ? SummaryProfile.fromMap(fields) : current;
        return encode(db, fields, profile);
    }

    private static boolean isRecordedGap(Object gap) {
        return !(gap instanceof Map<?, ?> fields
                && COLLECTION_NOT_ENABLED.equals(fields.get(REASON)));
    }

    private static DailySummaryCoverage encode(
            Db db, Map<String, Object> fields, SummaryProfile profile) {
        fields.put("styleMode", profile.styleMode().name());
        fields.put("speakerMode", profile.speakerMode().name());
        int messages = ((Number) fields.get(MESSAGES)).intValue();
        boolean isIncomplete = !((List<?>) fields.get(GAPS)).isEmpty();
        return new DailySummaryCoverage(db.json(fields), profile, messages, isIncomplete);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> decode(Db db, String json) {
        return db.parse(json, LinkedHashMap.class);
    }

    record Capture(Window window, Counts counts, List<Gap> gaps) {}

    record Window(Instant from, Instant to, Instant snapshotAt) {}

    record Counts(int messages, long nonText) {}

    record Reopen(boolean removesMissingCollection, boolean isOfficialQq) {}

    record Gap(Instant from, Instant to, String reason, boolean isSynthetic) {
        private FrozenGap freeze() {
            return new FrozenGap(encodeTime(from), encodeTime(to), reason);
        }

        private Object encodeTime(Instant time) {
            if (isSynthetic || time == null) {
                return time;
            }
            return Timestamp.from(time);
        }
    }

    private record FrozenCoverage(
            @JsonInclude(JsonInclude.Include.NON_NULL) String sourceMode,
            @JsonInclude(JsonInclude.Include.NON_NULL) String scopeNotice,
            int messages,
            long nonTextMessages,
            List<FrozenGap> gaps,
            Instant windowStart,
            Instant windowEnd,
            Instant snapshotAt,
            String latePolicy,
            String styleMode,
            String speakerMode) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private record FrozenGap(
            @JsonProperty("starts_at") Object start,
            @JsonProperty("ends_at") Object end,
            String reason) {}
}
