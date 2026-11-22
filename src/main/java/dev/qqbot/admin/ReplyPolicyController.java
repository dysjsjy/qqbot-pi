/**
 * [INPUT]: 依赖 ReplyPolicyService 与已认证的网页操作者。
 * [OUTPUT]: 提供群回复设置读取和带版本的更新接口。
 * [POS]: 网页写入与群内写入共享策略参数约束。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.admin;

import dev.qqbot.message.ReplyPolicyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/groups/{id}/reply-policy")
public class ReplyPolicyController {
    public record Input(
            @NotBlank String mode,
            @NotNull Double probability,
            @NotNull Integer energyGain,
            @NotNull Integer energyCost,
            @NotNull @Min(0) Long revision) {}

    private final ReplyPolicyService policies;

    public ReplyPolicyController(ReplyPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping
    public Map<String, Object> get(@PathVariable UUID id) {
        return policies.get(id);
    }

    @PutMapping
    public Map<String, Object> put(
            @PathVariable UUID id, @Valid @RequestBody Input input, Principal actor) {
        return policies.save(
                id,
                new ReplyPolicyService.Patch(
                        input.mode(), input.probability(), input.energyGain(), input.energyCost()),
                input.revision(),
                "WEB",
                actor.getName());
    }
}
