/**
 * [INPUT]: 依赖 TraceQueries 与管理员会话。
 * [OUTPUT]: 提供片段、热点及运行详情 HTTP 接口。
 * [POS]: 查询参数和 HTTP 错误的边界。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.admin;

import dev.qqbot.trace.SpanRecord;
import dev.qqbot.trace.TraceQueries;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/traces")
public class TraceController {
    private final TraceQueries queries;

    public TraceController(TraceQueries queries) {
        this.queries = queries;
    }

    @GetMapping
    public List<SpanRecord> list(
            @RequestParam(required = false) UUID groupId,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return queries.list(groupId, kind, limit, offset);
    }

    @GetMapping("/stats")
    public List<TraceQueries.Hotspot> stats(@RequestParam(defaultValue = "24") int hours) {
        return queries.stats(hours);
    }

    @GetMapping("/{runId}")
    public TraceQueries.Timeline detail(@PathVariable UUID runId) {
        return queries.detail(runId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badKind(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(Map.of("message", "Unsupported kind"));
    }
}
