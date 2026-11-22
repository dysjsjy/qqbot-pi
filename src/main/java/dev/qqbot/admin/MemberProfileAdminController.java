/**
 * [INPUT]: 依赖画像策略服务、画像业务服务与管理员会话。
 * [OUTPUT]: 暴露群级策略、成员纠偏、证据核验与作业接口。
 * [POS]: 实验功能的 HTTP 薄层；范围、配额与写入由业务层验证。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.admin;

import dev.qqbot.memory.MemberProfilePolicyService;
import dev.qqbot.memory.MemberProfileService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/groups/{id}/experimental/member-profiles")
public class MemberProfileAdminController {
    private final MemberProfilePolicyService policies;
    private final MemberProfileService profiles;

    public MemberProfileAdminController(
            MemberProfilePolicyService policies, MemberProfileService profiles) {
        this.policies = policies;
        this.profiles = profiles;
    }

    @GetMapping("/policy")
    public MemberProfilePolicyService.View policy(@PathVariable UUID id) {
        return policies.get(id);
    }

    @PutMapping("/policy")
    public MemberProfilePolicyService.View save(
            @PathVariable UUID id, @RequestBody MemberProfilePolicyService.Policy policy) {
        return policies.save(id, policy);
    }

    @GetMapping("/list")
    public MemberProfileService.Page list(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "") String state,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return profiles.list(id, state, q, limit, offset);
    }

    @GetMapping("/detail")
    public MemberProfileService.Detail detail(
            @PathVariable UUID id, @RequestParam String senderId) {
        return profiles.detail(id, senderId);
    }

    @PutMapping("/member")
    public MemberProfileService.Detail edit(
            @PathVariable UUID id, @RequestBody MemberProfileService.Edit input) {
        return profiles.edit(id, input);
    }

    @DeleteMapping("/member")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable UUID id, @RequestParam String senderId) {
        profiles.clear(id, senderId);
    }

    @DeleteMapping("/observations/{obsId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @PathVariable UUID obsId) {
        profiles.deleteObservation(id, obsId);
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> refresh(
            @PathVariable UUID id, @RequestBody MemberProfileService.Refresh input) {
        return Map.of("jobIds", profiles.refresh(id, input));
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> jobs(
            @PathVariable UUID id, @RequestParam(defaultValue = "50") int limit) {
        return profiles.jobs(id, limit);
    }

    @PostMapping("/jobs/{jobId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> retry(@PathVariable UUID id, @PathVariable UUID jobId) {
        return Map.of("jobId", profiles.retry(id, jobId));
    }
}
