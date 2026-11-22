package dev.qqbot.memory;

import dev.qqbot.persistence.Db;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MarkdownSearch {
    private final Db db;
    private final MemorySearchIndex index;
    private final Clock clock;

    @Autowired
    public MarkdownSearch(Db db, MemorySearchIndex index, Clock clock) {
        this.db = db;
        this.index = index;
        this.clock = clock;
    }

    public MarkdownSearch(Db db, MarkdownMemory files) {
        this(db, new MemorySearchIndex(db, files, Clock.systemUTC()), Clock.systemUTC());
    }

    public List<MemorySource> search(UUID groupId, String question, int requested, UUID exclude) {
        var group = db.one("select epoch,enabled,event_seq from group_config where id=?", groupId);
        if (group == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "群配置不存在，请刷新列表");
        }
        if (!Boolean.TRUE.equals(group.get("enabled"))) {
            return List.of();
        }
        long epoch = ((Number) group.get("epoch")).longValue();
        var origin =
                exclude == null
                        ? null
                        : db.one(
                                "select event_seq,occurred_at from group_message where id=? and group_id=? and epoch=?",
                                exclude,
                                groupId,
                                epoch);
        if (exclude != null && (origin == null || origin.get("event_seq") == null)) {
            return List.of();
        }
        Instant now = clock.instant();
        long before =
                origin == null
                        ? Math.addExact(((Number) group.get("event_seq")).longValue(), 1)
                        : ((Number) origin.get("event_seq")).longValue();
        var scope =
                new MemoryScope(
                        groupId,
                        epoch,
                        exclude,
                        before,
                        origin == null ? now : ((Timestamp) origin.get("occurred_at")).toInstant(),
                        now);
        return index
                .lexical(scope, question == null ? "" : question, QueryMode.HISTORY, 200)
                .evidence()
                .stream()
                .limit(Math.min(10, Math.max(1, requested)))
                .map(
                        item ->
                                new MemorySource(
                                        id(item),
                                        name(item),
                                        item.occurredAt(),
                                        item.body(),
                                        item.score()))
                .toList();
    }

    private static String id(Evidence item) {
        if (item.kind() == SourceKind.MANUAL) {
            return "MEMORY.md";
        }
        if (item.kind() == SourceKind.RAW) {
            return item.messageIds().get(0).toString();
        }
        return item.sourceKey().split(":")[1];
    }

    private static String name(Evidence item) {
        return switch (item.kind()) {
            case MANUAL -> "长期记忆 · MEMORY.md";
            case SUMMARY -> "自动概括 · summaries/" + id(item) + ".md";
            default -> "原始记录 · raw/" + id(item) + ".md";
        };
    }
}
