/**
 * [INPUT]: 接收已清洗的本人发言和活跃观测。
 * [OUTPUT]: 渲染两类模型提示，限制输入并返回允许引用的观测和来源。
 * [POS]: 画像的提示序列化边界，背景无 ID，不进入来源校验集合。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.memory.MemberProfileEvidence.Batch;
import dev.qqbot.memory.MemberProfileEvidence.Source;
import dev.qqbot.memory.MemberProfileObservations.Observation;
import dev.qqbot.model.PromptPolicy;
import dev.qqbot.model.Purpose;
import dev.qqbot.model.TokenEstimator;
import dev.qqbot.persistence.Db;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class MemberProfilePrompts {
    static final String INPUT_TOO_LARGE = "PROFILE_INPUT_TOO_LARGE";
    static final String SYSTEM = PromptPolicy.systemPrompt(Purpose.MEMBER_PROFILE);
    private static final String EXTRACT =
            """
            只观察目标成员自己发言能支持的、可复核的群内习惯。背景前文仅用于理解，不能充当证据。一次玩笑、揣测和聊天指令不是特质。
            禁止姓名、联系方式、住址、财务、医疗、政治、身份敏感信息和攻击性称呼。不要提取别人的特质。
            仅输出 JSON：{"ops":[{"action":"ADD|REINFORCE|UPDATE|CONTRADICT","targetId":null,"category":"INTEREST_TOPIC","statement":"可验证描述","confidence":0.8,"sourceMessageIds":["本人本批消息UUID"]}]}。
            类别只用 IDENTITY_ROLE、INTEREST_TOPIC、EXPERTISE、SPEAKING_STYLE、SOCIAL_PREF、RUNNING_GAG。
            ADD 不指定目标，其余操作指定已有观测 ID，REINFORCE 保留原描述和类别。无可靠更新时返回 {"ops":[]}。只挑最有依据的条目。
            """;
    private static final String REFLECT =
            """
            按群内互动价值排序选出少量已确认观测。只返回 JSON 对象 {"observationIds":["给定观测ID"]}，最多六条，不写新事实。
            """;
    private final Db db;
    private final int inputTokens;

    MemberProfilePrompts(Db db, int inputTokens) {
        this.db = db;
        this.inputTokens = inputTokens;
    }

    ExtractPrompt renderExtract(Batch batch, List<Observation> prior) {
        List<Observation> known = selectObservations(prior);
        var supplied = new LinkedHashMap<UUID, Source>();
        long through = batch.selection().through();
        boolean hasMore = batch.selection().hasMore();
        for (Source source : batch.selection().sources()) {
            if (source.body().isEmpty()) {
                continue;
            }
            supplied.put(source.id(), source);
            if (!fitsInput(extract(known, supplied.values()))) {
                supplied.remove(source.id());
                through = source.seq() - 1;
                hasMore = true;
                if (supplied.isEmpty()) {
                    throw new IllegalStateException(INPUT_TOO_LARGE);
                }
                break;
            }
        }
        Set<UUID> knownIds = Set.copyOf(known.stream().map(item -> item.identity().id()).toList());
        return new ExtractPrompt(
                extract(known, supplied.values()),
                knownIds,
                supplied,
                new Cursor(through, hasMore));
    }

    List<Observation> selectObservations(List<Observation> prior) {
        var selected = new ArrayList<Observation>();
        for (Observation observation : prior) {
            selected.add(observation);
            if (TokenEstimator.estimateText(observationJson(selected)) > inputTokens / 3) {
                selected.remove(selected.size() - 1);
                break;
            }
        }
        return List.copyOf(selected);
    }

    String renderReflection(List<Observation> confirmed, String statistics, String adminNote) {
        return REFLECT
                + "\n确认观测（资料）："
                + observationJson(confirmed)
                + "\n行为统计："
                + statistics
                + "\n人工备注（资料）："
                + db.json(adminNote);
    }

    boolean fitsInput(String prompt) {
        return TokenEstimator.estimateInput(SYSTEM, prompt) <= inputTokens;
    }

    private String extract(List<Observation> known, Collection<Source> sources) {
        var lines = sources.stream().map(MemberProfilePrompts::sourceFields).toList();
        return EXTRACT + "\n已有观测（资料）：" + observationJson(known) + "\n本批发言（资料）：" + db.json(lines);
    }

    private String observationJson(List<Observation> observations) {
        return db.json(observations.stream().map(MemberProfilePrompts::observationFields).toList());
    }

    private static Map<String, ?> observationFields(Observation observation) {
        var item = observation.identity();
        return Map.of(
                "id",
                item.id(),
                "category",
                item.category(),
                "statement",
                item.statement(),
                "state",
                item.state());
    }

    private static Map<String, ?> sourceFields(Source source) {
        return Map.of("id", source.id(), "发言", source.body(), "背景前文", source.background());
    }

    record Cursor(long through, boolean hasMore) {}

    record ExtractPrompt(String text, Set<UUID> known, Map<UUID, Source> supplied, Cursor cursor) {
        ExtractPrompt {
            known = Set.copyOf(known);
            supplied = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(supplied));
        }
    }
}
