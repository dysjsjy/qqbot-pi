/**
 * [INPUT]: 依赖活跃榜配置与快照查询服务。
 * [OUTPUT]: 提供每群活跃榜配置、版本更新与执行记录接口。
 * [POS]: 管理 HTTP 层；不接触数据库或日报任务。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.admin;

import dev.qqbot.schedule.ActivityScheduleService;
import dev.qqbot.schedule.DailyActivityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/groups/{id}/activity")
public class ActivityAdminController {
    public record Input(
            @NotNull Boolean enabled,
            @NotBlank @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String localTime,
            @NotNull @Min(1) @Max(20) Integer topN,
            @NotBlank @Pattern(regexp = "CONSOLE_ONLY|GROUP") String publishMode,
            @NotNull @Min(0) Long revision) {}

    private final ActivityScheduleService schedule;
    private final DailyActivityService rankings;

    public ActivityAdminController(
            ActivityScheduleService schedule, DailyActivityService rankings) {
        this.schedule = schedule;
        this.rankings = rankings;
    }

    @GetMapping("/schedule")
    public Map<String, Object> settings(@PathVariable UUID id) {
        return schedule.get(id);
    }

    @PutMapping("/schedule")
    public Map<String, Object> save(@PathVariable UUID id, @Valid @RequestBody Input input) {
        return schedule.save(
                id,
                input.enabled(),
                LocalTime.parse(input.localTime()),
                input.topN(),
                input.publishMode(),
                input.revision());
    }

    @GetMapping("/runs")
    public Map<String, Object> runs(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return rankings.runs(id, limit, offset);
    }
}
