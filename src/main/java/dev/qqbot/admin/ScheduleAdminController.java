/**
 * [INPUT]: 依赖 ScheduleSettingsService 与请求参数校验。
 * [OUTPUT]: 暴露 /api/admin/groups/{id}/schedule 与 /summary/yesterday 管理接口。
 * [POS]: admin 模块的日报配置控制器，不直接访问 Db。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.admin;

import dev.qqbot.schedule.ScheduleSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalTime;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/groups/{id}")
public class ScheduleAdminController {
    public record Input(
            boolean enabled,
            @NotBlank @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String localTime,
            String styleMode,
            String speakerMode) {
        public Input(boolean enabled, String localTime) {
            this(enabled, localTime, null, null);
        }
    }

    private final ScheduleSettingsService settings;

    public ScheduleAdminController(ScheduleSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/schedule")
    public Map<String, Object> get(@PathVariable UUID id) {
        return settings.get(id);
    }

    @PutMapping("/schedule")
    public Map<String, Object> save(@PathVariable UUID id, @Valid @RequestBody Input input) {
        return settings.save(
                id,
                input.enabled(),
                LocalTime.parse(input.localTime()),
                input.styleMode(),
                input.speakerMode());
    }

    @PostMapping("/summary/yesterday")
    public Map<String, Object> trigger(@PathVariable UUID id) {
        return settings.trigger(id);
    }
}
