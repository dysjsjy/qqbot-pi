/**
 * [INPUT]: 依赖 SQLite 链路片段和业务时钟。
 * [OUTPUT]: 返回链路片段、行程范围和聚合耗时。
 * [POS]: 跟踪读侧复用 SpanRecord，只在 JSON 边界解析动态属性。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.qqbot.persistence.Db;
import java.time.Clock;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TraceQueries {
    private static final Set<String> KINDS = Set.of("INGRESS", "AGENT", "DELIVERY", "BACKGROUND");
    private final Db db;
    private final Clock clock;

    public TraceQueries(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public List<SpanRecord> list(UUID groupId, String kind, int limit, int offset) {
        if (kind != null && !KINDS.contains(kind)) {
            throw new IllegalArgumentException("Unsupported kind");
        }
        String sql = "select * from trace_span where parent_span is null";
        List<Object> parameters = new ArrayList<>();
        if (groupId != null) {
            sql += " and group_id=?";
            parameters.add(groupId);
        }
        if (kind != null) {
            sql += " and kind=?";
            parameters.add(kind);
        }
        parameters.add(Math.max(1, Math.min(100, limit)));
        parameters.add(Math.max(0, offset));
        return db
                .list(sql + " order by start_us desc limit ? offset ?", parameters.toArray())
                .stream()
                .map(this::view)
                .toList();
    }

    public Timeline detail(UUID runId) {
        var spans =
                db
                        .list(
                                "select * from trace_span where run_id=? order by start_us,seq",
                                runId)
                        .stream()
                        .map(this::view)
                        .toList();
        if (spans.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        UUID groupId =
                spans.stream()
                        .map(SpanRecord::groupId)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);
        long startUs = spans.stream().mapToLong(SpanRecord::startUs).min().orElseThrow();
        long endUs =
                spans.stream()
                        .mapToLong(span -> span.startUs() + span.durationUs())
                        .max()
                        .orElseThrow();
        return new Timeline(runId, groupId, startUs, endUs, spans);
    }

    public List<Hotspot> stats(int hours) {
        long cutoff = (clock.millis() - Math.max(1, Math.min(168, hours)) * 3600000L) * 1000;
        var groups = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var row :
                db.list(
                        "select kind,name,status,duration_us,cpu_us,alloc_bytes from trace_span where start_us>=? order by start_us desc limit 20000",
                        cutoff)) {
            groups.computeIfAbsent(
                            row.get("kind") + "|" + row.get("name"), key -> new ArrayList<>())
                    .add(row);
        }
        List<Hotspot> result = new ArrayList<>();
        for (var rows : groups.values()) {
            long[] durations =
                    rows.stream().mapToLong(row -> number(row, "duration_us")).sorted().toArray();
            long total = Arrays.stream(durations).sum();
            long count = rows.size();
            long errors = rows.stream().filter(row -> "ERROR".equals(row.get("status"))).count();
            long avgCpu = rows.stream().mapToLong(row -> number(row, "cpu_us")).sum() / count;
            long avgAlloc =
                    rows.stream().mapToLong(row -> number(row, "alloc_bytes")).sum() / count;
            result.add(
                    new Hotspot(
                            (String) rows.get(0).get("kind"),
                            (String) rows.get(0).get("name"),
                            count,
                            errors,
                            total,
                            total / count,
                            percentile(durations, .50),
                            percentile(durations, .95),
                            durations[durations.length - 1],
                            avgCpu,
                            avgAlloc));
        }
        result.sort(Comparator.comparingLong(Hotspot::totalUs).reversed());
        return result;
    }

    private static long number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private static long percentile(long[] durations, double fraction) {
        return durations[Math.max(0, (int) Math.ceil(fraction * durations.length) - 1)];
    }

    private SpanRecord view(Map<String, Object> row) {
        return new SpanRecord(
                (UUID) row.get("id"),
                (UUID) row.get("run_id"),
                (UUID) row.get("group_id"),
                (String) row.get("parent_span"),
                (String) row.get("kind"),
                (String) row.get("name"),
                Math.toIntExact(number(row, "seq")),
                (String) row.get("status"),
                (String) row.get("error_code"),
                number(row, "start_us"),
                number(row, "duration_us"),
                number(row, "cpu_us"),
                number(row, "alloc_bytes"),
                number(row, "memory_bytes"),
                (Long) row.get("estimated_input_tokens"),
                (Long) row.get("input_tokens"),
                (Long) row.get("output_tokens"),
                (Long) row.get("cache_read_tokens"),
                (Long) row.get("cache_write_tokens"),
                (Long) row.get("reasoning_tokens"),
                attributes(row));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attributes(Map<String, Object> row) {
        try {
            var parsed = db.parse((String) row.get("attributes"), Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (IllegalArgumentException invalid) {
            return Map.of();
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Timeline(
            UUID runId, UUID groupId, long startUs, long endUs, List<SpanRecord> spans) {}

    public record Hotspot(
            String kind,
            String name,
            long count,
            long errors,
            long totalUs,
            long avgUs,
            long p50Us,
            long p95Us,
            long maxUs,
            long avgCpuUs,
            long avgAllocBytes) {}
}
