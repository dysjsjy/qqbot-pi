/**
 * [INPUT]: 依赖当前群水位、摘要策略、热留存和输入预算。
 * [OUTPUT]: 选取合格来源，在同一事务登记文档及来源。
 * [POS]: Markdown 摘要入队边界。生成阶段按已登记来源重新读取。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.MemoryProperties;
import dev.qqbot.RetentionProperties;
import dev.qqbot.persistence.Db;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class MarkdownSummaryPreparation {
    static final int BATCH_SIZE = 100;
    static final String SOURCE_TOO_LARGE = "SUMMARY_SOURCE_TOO_LARGE";
    private static final Duration RECENT_DELIVERY_WINDOW = Duration.ofDays(1);
    private static final String SOURCE_FILTER =
            """
            m.group_id=? and m.epoch=? and m.kind='TEXT' and m.event_seq is not null
              and m.event_seq<=? and m.occurred_at between ? and ?
              and not exists(select 1 from memory_document_source s where s.message_id=m.id and s.kind='SUMMARY')
            """;
    private final Db db;
    private final Clock clock;
    private final RetentionProperties retention;
    private final MemoryProperties budgets;

    MarkdownSummaryPreparation(
            Db db, Clock clock, RetentionProperties retention, MemoryProperties budgets) {
        this.db = db;
        this.clock = clock;
        this.retention = retention;
        this.budgets = budgets;
    }

    int prepare() {
        List<UUID> groups =
                db.query(
                        "select id from group_config where enabled order by id",
                        (row, index) -> UUID.fromString(row.getString(1)));
        int created = 0;
        for (UUID group : groups) {
            if (db.tx(() -> prepareGroup(group))) {
                created++;
            }
        }
        return created;
    }

    private boolean prepareGroup(UUID groupId) {
        var window = readWindow(groupId);
        var policy = loadPolicy(groupId);
        if (window.isEmpty() || policy.isEmpty()) {
            return false;
        }
        var scope = window.orElseThrow();
        var rules = policy.orElseThrow();
        if (rules.isV2() && !canPrepareV2(scope, rules)) {
            return false;
        }
        var candidates = readCandidates(scope);
        if (candidates.isEmpty() || (!rules.isV2() && candidates.size() < BATCH_SIZE)) {
            return false;
        }
        var selection =
                rules.isV2()
                        ? selectSources(candidates, budgets.inputTokens())
                        : new Selection(candidates, false);
        reserve(scope, rules, selection);
        return true;
    }

    private Optional<Window> readWindow(UUID groupId) {
        Instant now = clock.instant();
        return db.find(
                "select epoch,event_seq from group_config where id=? and enabled",
                (row, index) -> new Window(groupId, row.getLong(1), row.getLong(2), now),
                groupId);
    }

    private Optional<Policy> loadPolicy(UUID groupId) {
        return db.find(
                """
                select strategy_version,summary_trigger_tokens,summary_cooldown_minutes
                from memory_policy where group_id=? and mode='SUMMARY'
                """,
                (row, index) ->
                        new Policy(
                                row.getInt(1), row.getLong(2), Duration.ofMinutes(row.getLong(3))),
                groupId);
    }

    private boolean canPrepareV2(Window window, Policy policy) {
        return hasRecentDelivery(window)
                && isCooldownComplete(window, policy.cooldown())
                && pendingBytes(window) >= policy.triggerBytes();
    }

    private boolean hasRecentDelivery(Window window) {
        return db.find(
                        """
                select 1 from outbox o join agent_run a on a.id=o.agent_run_id
                  and a.group_id=o.group_id and a.epoch=o.epoch
                where o.group_id=? and o.epoch=? and o.state='SENT' and a.state='SUCCEEDED'
                  and o.result_key=cast(a.id as text) and o.event_seq<=?
                  and o.delivered_at between ? and ? limit 1
                """,
                        (row, index) -> true,
                        window.groupId(),
                        window.epoch(),
                        window.sequence(),
                        window.now().minus(RECENT_DELIVERY_WINDOW),
                        window.now())
                .isPresent();
    }

    private boolean isCooldownComplete(Window window, Duration cooldown) {
        var last =
                db.find(
                        """
                select finished_at from memory_document where group_id=? and epoch=?
                  and kind='SUMMARY' and state='READY' and finished_at is not null
                order by finished_at desc limit 1
                """,
                        (row, index) -> Instant.ofEpochMilli(row.getLong(1)),
                        window.groupId(),
                        window.epoch());
        return last.isEmpty() || !window.now().isBefore(last.orElseThrow().plus(cooldown));
    }

    private long pendingBytes(Window window) {
        return db.find(
                        "select coalesce(sum(length(cast(m.body as blob))),0) from group_message m where "
                                + SOURCE_FILTER,
                        (row, index) -> row.getLong(1),
                        sourceArguments(window))
                .orElseThrow();
    }

    private List<MarkdownSummaryText.Source> readCandidates(Window window) {
        return db.query(
                "select m.id,m.occurred_at,m.sender_name,m.body from group_message m where "
                        + SOURCE_FILTER
                        + " order by m.event_seq limit "
                        + BATCH_SIZE,
                MarkdownSummaryText.Source::read,
                sourceArguments(window));
    }

    private Object[] sourceArguments(Window window) {
        return new Object[] {
            window.groupId(),
            window.epoch(),
            window.sequence(),
            retention.hotCutoff(window.now()),
            window.now()
        };
    }

    static Selection selectSources(List<MarkdownSummaryText.Source> candidates, int inputLimit) {
        List<MarkdownSummaryText.Source> selected = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        for (var candidate : candidates) {
            MarkdownSummaryText.appendSource(body, candidate);
            if (!MarkdownSummaryText.fitsV2(body.toString(), inputLimit)) {
                return selected.isEmpty()
                        ? new Selection(List.of(candidate), true)
                        : new Selection(selected, false);
            }
            selected.add(candidate);
        }
        return new Selection(selected, false);
    }

    private void reserve(Window window, Policy policy, Selection selection) {
        UUID document = UUID.randomUUID();
        boolean isOversized = selection.isOversized();
        // SIDE EFFECT: 文档与来源共用入队事务，部分写入失败时一起回滚。
        db.exec(
                """
                insert into memory_document(id,group_id,epoch,kind,source_count,strategy_version,state,error_code,finished_at)
                values(?,?,?,'SUMMARY',?,?,?,?,?)
                """,
                document,
                window.groupId(),
                window.epoch(),
                selection.sources().size(),
                policy.version(),
                isOversized ? "FAILED" : "QUEUED",
                isOversized ? SOURCE_TOO_LARGE : null,
                isOversized ? window.now() : null);
        for (var source : selection.sources()) {
            db.exec(
                    "insert into memory_document_source(document_id,message_id,kind) values(?,?,'SUMMARY')",
                    document,
                    source.id());
        }
    }

    record Selection(List<MarkdownSummaryText.Source> sources, boolean isOversized) {
        Selection {
            sources = List.copyOf(sources);
        }
    }

    private record Window(UUID groupId, long epoch, long sequence, Instant now) {}

    private record Policy(int version, long triggerBytes, Duration cooldown) {
        boolean isV2() {
            return version == 2;
        }
    }
}
