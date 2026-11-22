/**
 * [INPUT]: 依赖 GroupAdministration、GroupMembers 与已认证的网页操作者。
 * [OUTPUT]: 提供会话配置、成员查询和群管理员授权接口。
 * [POS]: 管理 HTTP 层；业务服务校验群边界和配置版本。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.admin;

import dev.qqbot.management.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/groups")
public class GroupAdminController {
    public record DeleteInput(@NotBlank String confirmName, @Min(0) long revision) {}

    public record MemberAdminInput(
            @NotBlank @Size(max = 2000) String senderId,
            @NotNull Boolean granted,
            @NotNull @Min(0) Long revision) {}

    private final GroupAdministration groups;
    private final GroupMembers members;

    public GroupAdminController(GroupAdministration groups, GroupMembers members) {
        this.groups = groups;
        this.members = members;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return groups.list();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return groups.get(id);
    }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody GroupSettings input) {
        return groups.create(input);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable UUID id, @Valid @RequestBody GroupSettings input) {
        return groups.update(id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @Valid @RequestBody DeleteInput input) {
        groups.delete(id, input.confirmName(), input.revision());
    }

    @GetMapping("/{id}/members")
    public Map<String, Object> members(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "messages") String sort,
            @RequestParam(defaultValue = "false") boolean adminOnly,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return members.list(id, days, q, sort, adminOnly, limit, offset);
    }

    @GetMapping("/{id}/members/detail")
    public Map<String, Object> member(@PathVariable UUID id, @RequestParam String senderId) {
        return members.detail(id, senderId);
    }

    @PutMapping("/{id}/members/admin")
    public Map<String, Object> admin(
            @PathVariable UUID id, @Valid @RequestBody MemberAdminInput input, Principal actor) {
        return groups.setMemberAdmin(
                id, input.senderId(), input.granted(), input.revision(), actor.getName());
    }
}
