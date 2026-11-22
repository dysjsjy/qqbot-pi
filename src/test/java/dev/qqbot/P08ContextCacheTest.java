package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.memory.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class P08ContextCacheTest {
    @Test
    void probe() {
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        UUID group = UUID.randomUUID(), conversation = UUID.randomUUID();
        var request =
                new ContextRequest(
                        new MemoryScope(group, 2, null, 100, now, now),
                        conversation,
                        0,
                        "A",
                        "发布事项",
                        QueryMode.CHAT);
        var history = new ArrayList<HistoryEvent>();
        for (int index = 0; index < 80; index++) {
            history.add(
                    new HistoryEvent(
                            "message:" + UUID.randomUUID() + ":0",
                            index,
                            now.minusSeconds(100 - index),
                            Role.USER,
                            "A",
                            "甲",
                            "发布讨论".repeat(50),
                            conversation,
                            0L,
                            null,
                            index == 79));
        }
        var assembler = new ContextAssembler(MemoryProperties.defaults(), new ObjectMapper());
        var expected = assembler.assemble(request, "人工资料", history, List.of(), "工作摘要");
        for (int index = 0; index < 20; index++) {
            assembler.assemble(request, "人工资料", history, List.of(), "工作摘要");
        }
        long started = System.nanoTime();
        for (int index = 0; index < 200; index++) {
            assertEquals(expected, assembler.assemble(request, "人工资料", history, List.of(), "工作摘要"));
        }
        System.out.println(
                "P08_CONTEXT_PROBE avgMicros=" + (System.nanoTime() - started) / 200 / 1000);
    }
}
