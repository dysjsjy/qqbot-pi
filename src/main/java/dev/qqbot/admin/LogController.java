package dev.qqbot.admin;

import dev.qqbot.management.ManagementQueries;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class LogController {
    private final RuntimeLogs logs;
    private final ManagementQueries queries;

    public LogController(RuntimeLogs logs, ManagementQueries queries) {
        this.logs = logs;
        this.queries = queries;
    }

    @GetMapping("/logs")
    public Map<String, Object> logs(
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "") String level,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "100") int limit) {
        return logs.read(
                after, level, query.length() > 200 ? query.substring(0, 200) : query, limit);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return queries.audit(limit, offset);
    }
}
