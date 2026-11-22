/**
 * [INPUT]: 依赖 MemoryAdministration 与管理员会话。
 * [OUTPUT]: 暴露记忆状态、留存概览、搜索、重试和清除 HTTP 接口。
 * [POS]: 管理 API 薄层；所有读写委托记忆服务。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.admin;

import dev.qqbot.memory.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/groups/{id}/memory")
public class MemoryAdminController {
    public record ClearInput(@NotBlank String confirmName) {}

    private final MemoryAdministration memory;

    public MemoryAdminController(MemoryAdministration memory) {
        this.memory = memory;
    }

    @GetMapping("/status")
    public Map<String, Object> status(@PathVariable UUID id) {
        return memory.status(id);
    }

    @GetMapping("/retention")
    public MemoryAdministration.RetentionOverview retention(@PathVariable UUID id) {
        return memory.retention(id);
    }

    @PostMapping("/search")
    public List<MemorySource> search(
            @PathVariable UUID id, @RequestBody Map<String, String> input) {
        return memory.search(id, input.getOrDefault("question", ""));
    }

    @PostMapping("/retry")
    public Map<String, Object> retry(@PathVariable UUID id) {
        return memory.retry(id);
    }

    @DeleteMapping
    public void clear(@PathVariable UUID id, @Valid @RequestBody ClearInput input) {
        memory.clear(id, input.confirmName());
    }
}
