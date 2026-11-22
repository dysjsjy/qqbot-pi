/**
 * [INPUT]: 依赖用量读模型和记忆服务。
 * [OUTPUT]: 提供日用量与上下文统计 HTTP 接口。
 * [POS]: 解析业务日期并委托只读查询。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.admin;

import dev.qqbot.memory.MemoryService;
import dev.qqbot.usage.UsageQueries;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/groups/{groupId}/memory")
public class MemoryUsageController {
    private final UsageQueries usage;
    private final MemoryService memory;

    public MemoryUsageController(UsageQueries usage, MemoryService memory) {
        this.usage = usage;
        this.memory = memory;
    }

    @GetMapping("/usage")
    public UsageQueries.DailyUsage usage(
            @PathVariable UUID groupId, @RequestParam(required = false) String date) {
        try {
            return usage.daily(groupId, date == null ? null : LocalDate.parse(date));
        } catch (DateTimeParseException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_USAGE_DATE");
        }
    }

    @GetMapping("/context-stats")
    public Map<String, Object> stats(
            @PathVariable UUID groupId, @RequestParam(required = false) UUID runId) {
        return runId == null
                ? memory.latestContextStats(groupId)
                : memory.contextStats(groupId, runId);
    }
}
