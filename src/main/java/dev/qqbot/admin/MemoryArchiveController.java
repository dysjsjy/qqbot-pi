/**
 * [INPUT]: 依赖 MarkdownArchive 与管理员会话。
 * [OUTPUT]: 暴露归档策略、文档分页和归档重试接口。
 * [POS]: 归档管理 HTTP 薄层；文档范围由记忆服务验证。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.admin;

import dev.qqbot.memory.MarkdownArchive;
import dev.qqbot.memory.RawArchiveService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/groups/{groupId}/memory")
public class MemoryArchiveController {
    private final MarkdownArchive archive;

    public MemoryArchiveController(MarkdownArchive archive) {
        this.archive = archive;
    }

    @GetMapping("/policy")
    public Map<String, Object> policy(@PathVariable UUID groupId) {
        return archive.status(groupId);
    }

    @PutMapping("/policy")
    public Map<String, Object> configure(
            @PathVariable UUID groupId, @RequestBody Map<String, String> input) {
        return archive.configure(groupId, input.get("mode"));
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> documents(
            @PathVariable UUID groupId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return archive.documents(groupId, limit, offset);
    }

    @GetMapping("/documents/{id}")
    public Map<String, Object> document(
            @PathVariable UUID groupId,
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") int limit) {
        return archive.document(groupId, id, cursor, limit);
    }

    @PostMapping("/raw-archive/retry")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public RawArchiveService.Status retryRaw(@PathVariable UUID groupId) {
        return archive.retryRaw(groupId);
    }

    @PostMapping("/documents/{id}/retry")
    public Map<String, Object> retry(@PathVariable UUID groupId, @PathVariable UUID id) {
        return archive.retry(groupId, id);
    }
}
