package dev.qqbot.admin;

import dev.qqbot.memory.MarkdownMemory;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/groups/{id}/memory/markdown")
public class MarkdownMemoryController {
    private final MarkdownMemory memory;

    public MarkdownMemoryController(MarkdownMemory memory) {
        this.memory = memory;
    }

    @GetMapping
    public Map<String, String> read(@PathVariable UUID id) {
        return Map.of("content", memory.read(id));
    }

    @PutMapping
    public Map<String, String> write(
            @PathVariable UUID id, @RequestBody Map<String, String> input) {
        memory.write(id, input.get("content"));
        return read(id);
    }
}
