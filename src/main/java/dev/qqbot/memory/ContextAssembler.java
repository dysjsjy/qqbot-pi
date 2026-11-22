/**
 * [INPUT]: 依赖已冻结的会话输入与提示渲染。
 * [OUTPUT]: 返回符合总预算和分层上限的上下文、引用和统计。
 * [POS]: 决定候选优先级和预算取舍。由提示渲染器编码已选片段。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.MemberProfileProperties;
import dev.qqbot.MemoryProperties;
import dev.qqbot.memory.ContextAssembler.ContextBlock.Background;
import dev.qqbot.memory.ContextAssembler.ContextBlock.History;
import dev.qqbot.memory.ContextAssembler.ContextBlock.Layer;
import dev.qqbot.memory.ContextAssembler.ContextBlock.Source;
import dev.qqbot.memory.ContextPromptRenderer.Rendered;
import dev.qqbot.memory.ContextPromptRenderer.View;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ContextAssembler {
    private static final String LAYER_BUDGET = "LAYER_BUDGET";
    private static final String TOTAL_BUDGET = "TOTAL_BUDGET";
    private static final String DUPLICATE_SOURCE = "DUPLICATE_SOURCE";
    private static final String TRUNCATED = "TRUNCATED";
    private static final List<String> REASONS =
            List.of(LAYER_BUDGET, TOTAL_BUDGET, DUPLICATE_SOURCE, TRUNCATED);
    private static final int RECENT_GROUP_EVENTS = 20;
    private static final int MIN_EXCERPT_CODE_POINTS = 100;

    private final int inputTokenLimit;
    private final Map<Layer, Integer> layerLimits;
    private final ContextPromptRenderer renderer;

    /** 使用默认画像预算创建纯组装器。 */
    public ContextAssembler(MemoryProperties properties, ObjectMapper json) {
        this(properties, json, MemberProfileProperties.defaults());
    }

    /** 从运行配置读取总预算和画像预算。 */
    @Autowired
    public ContextAssembler(
            MemoryProperties properties, ObjectMapper json, MemberProfileProperties profiles) {
        this.inputTokenLimit = properties.inputTokens();
        this.layerLimits =
                Map.of(
                        Layer.HISTORY,
                        3000,
                        Layer.MANUAL,
                        800,
                        Layer.EVIDENCE,
                        1600,
                        Layer.SUMMARY,
                        1000,
                        Layer.PROFILE,
                        profiles.tokenLimit());
        this.renderer = new ContextPromptRenderer(json, profiles.tokenLimit());
        this.profileLimit = profiles.injectionLimit();
    }

    /** 组装不含画像的上下文；非空输入必须来自同一会话范围。 */
    public ContextBundle assemble(ContextRequest request, Inputs inputs) {
        return assemble(request, inputs, MemberProfileContext.Selection.empty());
    }

    /** 按预算挑选来源；无足够空间容纳当前问题与系统提示时拒绝请求。 */
    public ContextBundle assemble(
            ContextRequest request, Inputs inputs, MemberProfileContext.Selection profiles) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(inputs, "inputs must not be null");
        Objects.requireNonNull(profiles, "profiles must not be null");
        Inputs compacted = compactHistory(request, inputs);
        MemberProfileContext.Selection applicable = resolveProfiles(request, profiles);
        View view = labelParticipants(request, compacted.history(), applicable);
        Rendered baseline = requireBaseline(view);
        Ranking ranked = rankCandidates(compacted, view, applicable.cards());
        Selection selection = selectWithinBudget(view, baseline, ranked);
        return assembleBundle(request, inputs.manual(), ranked, selection);
    }

    /** 兼容使用独立来源参数的既有调用方；新代码传入冻结输入。 */
    public ContextBundle assemble(
            ContextRequest request,
            String manual,
            List<HistoryEvent> history,
            List<Evidence> evidence,
            String summary) {
        return assemble(request, new Inputs(manual, history, evidence, summary));
    }

    /** 兼容使用独立来源参数的既有调用方；新代码传入冻结输入。 */
    public ContextBundle assemble(
            ContextRequest request,
            String manual,
            List<HistoryEvent> history,
            List<Evidence> evidence,
            String summary,
            MemberProfileContext.Selection profiles) {
        return assemble(request, new Inputs(manual, history, evidence, summary), profiles);
    }

    private static Inputs compactHistory(ContextRequest request, Inputs inputs) {
        List<HistoryEvent> history =
                RetrievalGate.microCompactHistory(
                        inputs.history(),
                        request.conversationId(),
                        request.generation(),
                        RECENT_GROUP_EVENTS);
        return new Inputs(inputs.manual(), history, inputs.candidates(), inputs.summary());
    }

    private static MemberProfileContext.Selection resolveProfiles(
            ContextRequest request, MemberProfileContext.Selection profiles) {
        return request.mode() == QueryMode.CHAT && profiles.enabled()
                ? profiles
                : MemberProfileContext.Selection.empty();
    }

    private static View labelParticipants(
            ContextRequest request,
            List<HistoryEvent> history,
            MemberProfileContext.Selection profiles) {
        var senders = new TreeSet<String>();
        senders.add(request.senderId());
        for (HistoryEvent event : history) {
            senders.add(event.senderId());
        }
        for (var card : profiles.cards()) {
            senders.add(card.senderId());
        }
        return new View(
                request, MemberLabels.labels(request.scope().groupId(), senders), profiles.warm());
    }

    private Rendered requireBaseline(View view) {
        Rendered baseline = renderer.renderPrompt(view, List.of());
        if (!fits(baseline)) {
            throw new IllegalStateException("CONTEXT_TOO_LARGE");
        }
        return baseline;
    }

    private Selection selectWithinBudget(View view, Rendered baseline, Ranking ranked) {
        var selection = new Selection(view, baseline);
        if (ranked.hasDuplicates()) {
            selection.reasons.add(DUPLICATE_SOURCE);
        }
        for (ContextBlock block : ranked.blocks()) {
            selection.consider(block);
        }
        return selection;
    }

    private ContextBundle assembleBundle(
            ContextRequest request, String manual, Ranking ranked, Selection selection) {
        Rendered result = selection.rendered;
        ContextStats stats = buildStats(manual, ranked.offered(), selection);
        CitationMode citation = requiredCitation(request.mode(), result.evidence());
        return new ContextBundle(
                result.prompts().system(),
                result.prompts().user(),
                result.evidence(),
                stats,
                citation);
    }

    private static ContextStats buildStats(
            String manual, List<String> offered, Selection selection) {
        var keys = new LinkedHashSet<String>();
        for (ContextBlock block : selection.blocks) {
            keys.add(block.key());
        }
        List<String> dropped = offered.stream().filter(key -> !keys.contains(key)).toList();
        List<String> reasons = REASONS.stream().filter(selection.reasons::contains).toList();
        // 摘要覆盖完整人工文档，不随选入预算的段落改变。
        String manualHash = MemberLabels.hash(manual);
        return new ContextStats(
                selection.rendered.total(),
                selection.rendered.layers(),
                List.copyOf(keys),
                dropped,
                reasons,
                manualHash);
    }

    private static CitationMode requiredCitation(QueryMode mode, List<Evidence> evidence) {
        if (evidence.isEmpty()) {
            return CitationMode.NONE;
        }
        return mode == QueryMode.HISTORY ? CitationMode.REQUIRED : CitationMode.OPTIONAL;
    }

    private boolean fits(Rendered rendered) {
        return rendered.total() <= inputTokenLimit && fitsLayers(rendered);
    }

    private boolean fitsLayers(Rendered rendered) {
        return layerLimits.entrySet().stream()
                .allMatch(limit -> rendered.layers().get(limit.getKey().key()) <= limit.getValue());
    }

    /** 冻结按同一会话范围读取的背景与证据。 */
    public record Inputs(
            String manual, List<HistoryEvent> history, List<Evidence> candidates, String summary) {
        public Inputs {
            Objects.requireNonNull(manual, "manual must not be null");
            Objects.requireNonNull(summary, "summary must not be null");
            history = List.copyOf(history);
            candidates = List.copyOf(candidates);
        }
    }

    private final class Selection {
        private final View view;
        private final List<ContextBlock> blocks = new ArrayList<>();
        private final Set<String> reasons = new HashSet<>();
        private Rendered rendered;

        Selection(View view, Rendered baseline) {
            this.view = view;
            this.rendered = baseline;
        }

        void consider(ContextBlock block) {
            Rendered trial = renderWith(block);
            if (fits(trial)) {
                add(new Trial(block, trial));
                return;
            }
            if (block instanceof History history && history.isTruncatable()) {
                Optional<Trial> excerpt = findExcerpt(history);
                if (excerpt.isPresent()) {
                    add(excerpt.orElseThrow());
                    reasons.add(TRUNCATED);
                    return;
                }
            }
            reasons.add(fitsLayers(trial) ? TOTAL_BUDGET : LAYER_BUDGET);
        }

        private Optional<Trial> findExcerpt(History block) {
            String body = block.body();
            int lower = MIN_EXCERPT_CODE_POINTS;
            int upper = body.codePointCount(0, body.length()) - 1;
            Trial best = null;
            while (lower <= upper) {
                int length = (lower + upper) >>> 1;
                int end = body.offsetByCodePoints(0, length);
                History candidate = block.withBody(body.substring(0, end) + "（节选）");
                Rendered trial = renderWith(candidate);
                if (fits(trial)) {
                    best = new Trial(candidate, trial);
                    lower = length + 1;
                } else {
                    upper = length - 1;
                }
            }
            return Optional.ofNullable(best);
        }

        private Rendered renderWith(ContextBlock block) {
            var trial = new ArrayList<>(blocks);
            trial.add(block);
            return renderer.renderPrompt(view, trial);
        }

        private void add(Trial accepted) {
            blocks.add(accepted.block());
            rendered = accepted.rendered();
        }
    }

    private record Trial(ContextBlock block, Rendered rendered) {}

    private static final Duration ANSWER_WINDOW = Duration.ofHours(24);
    private static final int MAX_ANSWER_THREADS = 4;
    private static final int MAX_EVIDENCE = 4;
    private static final Comparator<HistoryEvent> RECENCY =
            Comparator.comparingLong(HistoryEvent::seq)
                    .reversed()
                    .thenComparing(HistoryEvent::sourceKey);

    private final int profileLimit;

    private Ranking rankCandidates(
            ContextAssembler.Inputs inputs,
            ContextPromptRenderer.View view,
            List<MemberProfileContext.Card> cards) {
        var ranking = new RankingBuilder();
        var history = new RecentHistory(view.request(), inputs.history(), ranking);
        history.offerReferences(ranking);
        history.offerAnswers(ranking);
        offerManual(ranking, inputs.manual());
        offerProfiles(ranking, cards, view);
        List<Source> sources = selectEvidence(inputs.candidates(), ranking);
        if (view.request().mode() == QueryMode.HISTORY) {
            ranking.offerAll(sources);
        }
        history.offerRemaining(ranking);
        offerSummary(ranking, inputs.summary());
        if (view.request().mode() == QueryMode.CHAT) {
            ranking.offerAll(sources);
        }
        return ranking.freeze();
    }

    private static void offerManual(RankingBuilder ranking, String manual) {
        List<String> paragraphs = SearchText.paragraphs(manual);
        // 摘要覆盖完整人工文档；段落键仅在文档内容不变时稳定。
        String contentHash = MemberLabels.hash(manual);
        for (int part = 0; part < paragraphs.size(); part++) {
            String text = paragraphs.get(part);
            if (!text.isBlank()) {
                ranking.offer(
                        new Background(
                                Layer.MANUAL, "manual:" + contentHash + ":" + part, text, part));
            }
        }
    }

    private void offerProfiles(
            RankingBuilder ranking,
            List<MemberProfileContext.Card> cards,
            ContextPromptRenderer.View view) {
        for (var card : cards.stream().limit(profileLimit).toList()) {
            String label = view.labels().get(card.senderId());
            var line = renderer.fitProfileLine(label, card);
            if (line.isPresent()) {
                ranking.offer(
                        new Background(Layer.PROFILE, "profile:" + label, line.orElseThrow(), 0));
            }
        }
    }

    private static void offerSummary(RankingBuilder ranking, String summary) {
        if (!summary.isBlank()) {
            // 摘要键覆盖当前摘要正文，不以文件路径区分同一内容。
            String key = "summary:latest:" + MemberLabels.hash(summary);
            ranking.offer(new Background(Layer.SUMMARY, key, summary, 0));
        }
    }

    private static List<Source> selectEvidence(List<Evidence> candidates, RankingBuilder ranking) {
        var selected = new ArrayList<Source>();
        var sourceIds = new HashSet<UUID>();
        var keys = new HashSet<String>();
        for (Evidence candidate : candidates) {
            ranking.offered.add(candidate.sourceKey());
            if (candidate.body().isBlank()
                    || !keys.add(candidate.sourceKey())
                    || candidate.messageIds().stream().anyMatch(sourceIds::contains)) {
                ranking.hasDuplicates = true;
                continue;
            }
            sourceIds.addAll(candidate.messageIds());
            selected.add(new Source(candidate));
            if (selected.size() == MAX_EVIDENCE) {
                break;
            }
        }
        return selected;
    }

    private static final class RecentHistory {
        private final ContextRequest request;
        private final Map<String, HistoryEvent> events;
        private final String latestAnswer;

        RecentHistory(ContextRequest request, List<HistoryEvent> history, RankingBuilder ranking) {
            this.request = request;
            this.events = orderHistory(history, ranking);
            this.latestAnswer =
                    events.values().stream()
                            .filter(this::isOwnAnswer)
                            .map(HistoryEvent::sourceKey)
                            .findFirst()
                            .orElse("");
        }

        private static Map<String, HistoryEvent> orderHistory(
                List<HistoryEvent> history, RankingBuilder ranking) {
            var ordered = new LinkedHashMap<String, HistoryEvent>();
            for (HistoryEvent event : history.stream().sorted(RECENCY).toList()) {
                if (ordered.putIfAbsent(event.sourceKey(), event) != null) {
                    ranking.hasDuplicates = true;
                }
            }
            return ordered;
        }

        void offerReferences(RankingBuilder ranking) {
            for (HistoryEvent event : events.values()) {
                if (event.explicitlyReferenced()) {
                    ranking.offer(block(event));
                }
            }
        }

        void offerAnswers(RankingBuilder ranking) {
            for (var thread : answerThreads().entrySet()) {
                HistoryEvent question = events.get(thread.getKey());
                if (question != null) {
                    ranking.offer(referenceOnly(question));
                }
                var ordered =
                        thread.getValue().stream()
                                .sorted(Comparator.comparingLong(HistoryEvent::seq))
                                .toList();
                for (HistoryEvent event : ordered) {
                    ranking.offer(block(event));
                }
            }
        }

        private Map<String, List<HistoryEvent>> answerThreads() {
            var threads = new LinkedHashMap<String, List<HistoryEvent>>();
            Instant cutoff = request.scope().evaluatedAt().minus(ANSWER_WINDOW);
            for (HistoryEvent event : events.values()) {
                if (!isOwnAnswer(event) || event.occurredAt().isBefore(cutoff)) {
                    continue;
                }
                String question = Objects.requireNonNullElse(event.replyToKey(), event.sourceKey());
                if (threads.containsKey(question) || threads.size() < MAX_ANSWER_THREADS) {
                    threads.computeIfAbsent(question, key -> new ArrayList<>()).add(event);
                }
            }
            return threads;
        }

        private boolean isOwnAnswer(HistoryEvent event) {
            return event.role() == Role.ASSISTANT
                    && request.conversationId().equals(event.conversationId())
                    && Objects.equals(request.generation(), event.generation());
        }

        void offerRemaining(RankingBuilder ranking) {
            for (HistoryEvent event : events.values()) {
                ranking.offer(block(event));
            }
        }

        private History block(HistoryEvent event) {
            boolean isTruncatable =
                    event.explicitlyReferenced() || event.sourceKey().equals(latestAnswer);
            return new History(event, event.body(), isTruncatable);
        }

        private static History referenceOnly(HistoryEvent event) {
            return new History(event, event.body(), event.explicitlyReferenced());
        }
    }

    private static final class RankingBuilder {
        private final Map<BlockIdentity, ContextBlock> blocks = new LinkedHashMap<>();
        private final Set<String> offered = new LinkedHashSet<>();
        private boolean hasDuplicates;

        void offer(ContextBlock block) {
            offered.add(block.key());
            blocks.putIfAbsent(new BlockIdentity(block.layer(), block.key()), block);
        }

        void offerAll(List<? extends ContextBlock> values) {
            for (ContextBlock block : values) {
                offer(block);
            }
        }

        Ranking freeze() {
            return new Ranking(List.copyOf(blocks.values()), List.copyOf(offered), hasDuplicates);
        }
    }

    private record BlockIdentity(Layer layer, String key) {}

    private record Ranking(
            List<ContextBlock> blocks, List<String> offered, boolean hasDuplicates) {}

    sealed interface ContextBlock
            permits ContextBlock.Background, ContextBlock.History, ContextBlock.Source {
        String key();

        String body();

        Layer layer();

        enum Layer {
            SYSTEM("system"),
            CURRENT("current"),
            MANUAL("manual"),
            PROFILE("profile"),
            HISTORY("history"),
            EVIDENCE("evidence"),
            SUMMARY("summary"),
            OVERHEAD("overhead");

            private final String key;

            Layer(String key) {
                this.key = key;
            }

            String key() {
                return key;
            }
        }

        record Background(Layer layer, String key, String body, int order)
                implements ContextBlock {}

        record History(HistoryEvent event, String body, boolean isTruncatable)
                implements ContextBlock {
            @Override
            public String key() {
                return event.sourceKey();
            }

            @Override
            public Layer layer() {
                return Layer.HISTORY;
            }

            History withBody(String text) {
                return new History(event, text, isTruncatable);
            }
        }

        record Source(Evidence evidence) implements ContextBlock {
            @Override
            public String key() {
                return evidence.sourceKey();
            }

            @Override
            public String body() {
                return evidence.body();
            }

            @Override
            public Layer layer() {
                return Layer.EVIDENCE;
            }
        }
    }
}
