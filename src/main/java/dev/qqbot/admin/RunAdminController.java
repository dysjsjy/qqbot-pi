package dev.qqbot.admin;

import dev.qqbot.management.ManagementQueries;
import dev.qqbot.schedule.DailySummaryService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/runs")
public class RunAdminController {
    private final ManagementQueries queries;
    private final DailySummaryService summaries;

    public RunAdminController(ManagementQueries queries, DailySummaryService summaries) {
        this.queries = queries;
        this.summaries = summaries;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(required = false) UUID groupId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return queries.runs(groupId, limit, offset);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return queries.run(id);
    }

    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(@PathVariable UUID id) {
        return summaries.retry(id);
    }
}
