/**
 * [INPUT]: 依赖 MemoryPolicyService 与管理会话。
 * [OUTPUT]: 提供带修订号的归档策略读取与更新接口。
 * [POS]: HTTP 校验边界。不暴露回复上下文模式。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.admin;

import dev.qqbot.memory.MemoryPolicyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/groups/{groupId}/memory/settings")
public class MemorySettingsController {
    public record Input(
            @NotNull Long revision,
            @NotNull String mode,
            @NotNull Integer strategyVersion,
            @NotNull Integer summaryTriggerTokens,
            @NotNull Integer summaryCooldownMinutes) {}

    private final MemoryPolicyService policies;

    public MemorySettingsController(MemoryPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping
    public Map<String, Object> get(@PathVariable UUID groupId) {
        return policies.settings(groupId);
    }

    @PutMapping
    public Map<String, Object> put(@PathVariable UUID groupId, @Valid @RequestBody Input input) {
        return policies.updateSettings(
                groupId,
                input.revision(),
                input.mode(),
                input.strategyVersion(),
                input.summaryTriggerTokens(),
                input.summaryCooldownMinutes());
    }
}
