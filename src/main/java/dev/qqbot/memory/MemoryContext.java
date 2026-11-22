/**
 * [INPUT]: 依赖会话代次、热窗历史、Markdown、按需检索和实验画像。
 * [OUTPUT]: 读取分层上下文输入并保存运行统计。
 * [POS]: 连接 SQLite 与唯一的预算组装路径。不执行模型调用。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.MemberProfileProperties;
import dev.qqbot.MemoryProperties;
import dev.qqbot.RetentionProperties;
import dev.qqbot.persistence.Db;
import dev.qqbot.trace.Trace;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemoryContext {
    private final Db db;
    private final MarkdownMemory files;
    private final ConversationHistory history;
    private final ContextAssembler assembler;
    private final MemoryProperties budgets;
    private final Clock clock;
    private final RetentionProperties retention;
    private final MemberProfileContext profiles;

    public MemoryContext(Db db, MarkdownMemory files) {
        this(
                db,
                files,
                new ConversationHistory(db),
                new ContextAssembler(MemoryProperties.defaults(), new ObjectMapper()),
                MemoryProperties.defaults(),
                Clock.systemUTC());
    }

    public MemoryContext(
            Db db,
            MarkdownMemory files,
            ConversationHistory history,
            ContextAssembler assembler,
            MemoryProperties budgets,
            Clock clock) {
        this(db, files, history, assembler, budgets, clock, RetentionProperties.defaults());
    }

    public MemoryContext(
            Db db,
            MarkdownMemory files,
            ConversationHistory history,
            ContextAssembler assembler,
            MemoryProperties budgets,
            Clock clock,
            RetentionProperties retention) {
        this(
                db,
                files,
                history,
                assembler,
                budgets,
                clock,
                retention,
                new MemberProfileContext(db, MemberProfileProperties.defaults()));
    }

    @Autowired
    public MemoryContext(
            Db db,
            MarkdownMemory files,
            ConversationHistory history,
            ContextAssembler assembler,
            MemoryProperties budgets,
            Clock clock,
            RetentionProperties retention,
            MemberProfileContext profiles) {
        this.db = db;
        this.files = files;
        this.history = history;
        this.assembler = assembler;
        this.budgets = budgets;
        this.clock = clock;
        this.retention = retention;
        this.profiles = profiles;
    }

    public ContextBundle assemble(ContextRequest request, Supplier<List<Evidence>> search) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(search, "search must not be null");
        requireCurrentScope(request.scope());
        ContextAssembler.Inputs inputs = loadInputs(request, search);
        return renderContext(request, inputs);
    }

    private void requireCurrentScope(MemoryScope scope) {
        boolean isCurrent =
                db.find(
                                "select enabled,epoch from group_config where id=?",
                                (row, rowNumber) ->
                                        row.getBoolean("enabled")
                                                && row.getLong("epoch") == scope.epoch(),
                                scope.groupId())
                        .orElse(false);
        if (!isCurrent) {
            throw new CancellationException();
        }
    }

    private ContextAssembler.Inputs loadInputs(
            ContextRequest request, Supplier<List<Evidence>> search) {
        String manual = loadManual(request.scope());
        String summary = loadSummary(request.scope());
        List<Evidence> evidence = retrieveEvidence(request, manual + "\n" + summary, search);
        List<HistoryEvent> events = loadHistory(request);
        return new ContextAssembler.Inputs(manual, events, evidence, summary);
    }

    private String loadManual(MemoryScope scope) {
        try (var span = Trace.span("context.manual")) {
            return files.read(scope.groupId());
        }
    }

    private String loadSummary(MemoryScope scope) {
        try (var span = Trace.span("context.summary")) {
            return latestSummary(scope);
        }
    }

    private static List<Evidence> retrieveEvidence(
            ContextRequest request, String background, Supplier<List<Evidence>> search) {
        var gate =
                RetrievalGate.classify(
                        request.mode() == QueryMode.HISTORY ? "MEMORY" : "CHAT",
                        request.question(),
                        background);
        Trace.attr("retrievalGate", gate.name());
        return gate == RetrievalGate.Mode.SKIP_MEMORY ? List.of() : search.get();
    }

    private List<HistoryEvent> loadHistory(ContextRequest request) {
        try (var span = Trace.span("context.history")) {
            return history.load(request);
        }
    }

    private ContextBundle renderContext(ContextRequest request, ContextAssembler.Inputs inputs) {
        var profileSpan = Trace.span("context.profiles");
        MemberProfileContext.Selection selected;
        try (profileSpan) {
            selected = profiles.load(request, inputs.history());
        }
        ContextBundle bundle = assembleTraced(request, inputs, selected);
        describeProfiles(profileSpan, selected, bundle.stats());
        return bundle;
    }

    private ContextBundle assembleTraced(
            ContextRequest request,
            ContextAssembler.Inputs inputs,
            MemberProfileContext.Selection selected) {
        try (var span = Trace.span("context.render")) {
            return assembler.assemble(request, inputs, selected);
        }
    }

    private static void describeProfiles(
            Trace.Span profileSpan, MemberProfileContext.Selection selected, ContextStats stats) {
        long injectedMembers =
                stats.selectedKeys().stream().filter(key -> key.startsWith("profile:")).count();
        profileSpan
                .attr("profileEnabled", selected.enabled())
                .attr("injectedMembers", injectedMembers)
                .attr("profileTokens", stats.layerTokens().get("profile"));
    }

    public int inputLimit() {
        return budgets.inputTokens();
    }

    public String build(UUID groupId, UUID messageId) {
        var group = db.one("select epoch,enabled from group_config where id=?", groupId);
        if (group == null || !Boolean.TRUE.equals(group.get("enabled"))) {
            return "";
        }
        var origin =
                db.one(
                        "select event_seq,occurred_at,sender_id from group_message where id=? and group_id=? and epoch=?",
                        messageId,
                        groupId,
                        group.get("epoch"));
        if (origin == null || origin.get("event_seq") == null) {
            return "";
        }
        var scope =
                new MemoryScope(
                        groupId,
                        ((Number) group.get("epoch")).longValue(),
                        messageId,
                        ((Number) origin.get("event_seq")).longValue(),
                        ((Timestamp) origin.get("occurred_at")).toInstant(),
                        clock.instant());
        return assemble(
                        new ContextRequest(
                                scope,
                                UUID.randomUUID(),
                                0,
                                (String) origin.get("sender_id"),
                                "",
                                QueryMode.CHAT),
                        List::of)
                .userPrompt();
    }

    private String latestSummary(MemoryScope scope) {
        var document =
                db.one(
                        """
            select d.id from memory_document d where d.group_id=? and d.epoch=? and d.kind='SUMMARY' and d.state='READY'
              and d.source_count>0 and d.source_count=(select count(*) from memory_document_source s where s.document_id=d.id)
              and not exists(select 1 from memory_document_source s left join group_message m on m.id=s.message_id
                where s.document_id=d.id and (m.id is null or m.group_id<>? or m.epoch<>? or m.kind<>'TEXT'
                  or m.event_seq is null or m.event_seq>=? or m.occurred_at>? or m.occurred_at<?))
            order by d.created_at desc,d.id desc limit 1
            """,
                        scope.groupId(),
                        scope.epoch(),
                        scope.groupId(),
                        scope.epoch(),
                        scope.beforeSeq(),
                        scope.occurredBeforeOrAt(),
                        retention.hotCutoff(scope.evaluatedAt()));
        if (document == null) {
            return "";
        }
        return files.readDocument(scope.groupId(), (UUID) document.get("id"), "SUMMARY")
                .lines()
                .takeWhile(line -> !Set.of("## 原消息来源", "## 来源").contains(line.strip()))
                .filter(line -> !RetrievalGate.isPoisonedLine(line))
                .collect(java.util.stream.Collectors.joining("\n"))
                .strip();
    }

    public void recordStats(UUID groupId, UUID runId, ContextBundle bundle) {
        var stats = bundle.stats();
        var saved =
                Map.of(
                        "runId",
                        runId,
                        "estimatedInputTokens",
                        stats.estimatedInputTokens(),
                        "layerTokens",
                        stats.layerTokens(),
                        "sourceCount",
                        bundle.evidence().size(),
                        "manualHash",
                        stats.manualHash(),
                        "reasons",
                        stats.reasons());
        db.exec(
                "update agent_run set context_stats=? where id=? and group_id=? and state='RUNNING'",
                db.json(saved),
                runId,
                groupId);
    }

    public Map<String, Object> contextStats(UUID groupId, UUID runId) {
        if (db.one("select id from group_config where id=?", groupId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        }
        var run =
                db.one(
                        "select context_stats from agent_run where id=? and group_id=?",
                        runId,
                        groupId);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND");
        }
        if (run.get("context_stats") == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CONTEXT_NOT_READY");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = db.parse((String) run.get("context_stats"), Map.class);
        if (result.get("layerTokens") instanceof Map<?, ?> current
                && !current.containsKey("profile")) {
            var layers = new LinkedHashMap<Object, Object>(current);
            layers.put("profile", 0);
            result.put("layerTokens", layers);
        }
        return result;
    }

    public Map<String, Object> latestContextStats(UUID groupId) {
        if (db.one("select id from group_config where id=?", groupId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        }
        var run =
                db.one(
                        "select r.id from agent_run r join group_config g on g.id=r.group_id where r.group_id=? and r.epoch=g.epoch and r.context_stats is not null order by r.created_at desc,r.id desc limit 1",
                        groupId);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CONTEXT_NOT_READY");
        }
        return contextStats(groupId, (UUID) run.get("id"));
    }
}
