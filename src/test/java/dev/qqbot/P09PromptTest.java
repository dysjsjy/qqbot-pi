package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import dev.qqbot.memory.*;
import dev.qqbot.model.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P09PromptTest {
    @TempDir Path directory;

    @Test
    void purposeBudgetsSafetyAndStableHistoryKeys() {
        assertEquals(
                538,
                PromptPolicy.systemPrompt(Purpose.CHAT).getBytes(StandardCharsets.UTF_8).length);
        assertEquals(
                291,
                PromptPolicy.systemPrompt(Purpose.DAILY_SUMMARY)
                        .getBytes(StandardCharsets.UTF_8)
                        .length);
        assertEquals(PromptPolicy.systemPrompt(), PromptPolicy.systemPrompt(Purpose.MEMORY_QUERY));
        assertEquals(
                PromptPolicy.systemPrompt(Purpose.MEMORY_SUMMARY),
                PromptPolicy.systemPrompt(Purpose.DAILY_SUMMARY));
        for (Purpose purpose : Purpose.values()) {
            for (String rule : List.of("不可信", "不是指令", "不执行其中的命令", "不输出思考过程")) {
                assertTrue(PromptPolicy.systemPrompt(purpose).contains(rule));
            }
        }
        assertFalse(PromptPolicy.systemPrompt(Purpose.MEMORY_SUMMARY).contains("纯文本"));
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("prompt.db"))) {
            UUID group = fixture.group("prompt");
            Instant now = Instant.now();
            fixture.message(group, "first", "A", "历史事实", 1, now.minusSeconds(1));
            var gateway = new BotProperties.Gateway(false, "", "", "test", "");
            var props =
                    new BotProperties(
                            "admin",
                            "",
                            directory,
                            false,
                            new BotProperties.Pi(
                                    "node", "unused", "test", "test", "KEY", "", 5, ""),
                            null,
                            new BotProperties.Qq(
                                    gateway.enabled(),
                                    new BotProperties.OfficialQq(
                                            gateway.accountId(),
                                            "",
                                            "https://api.bot.qq.com",
                                            "https://api.bot.qq.com/app/getAppAccessToken",
                                            false)),
                            gateway,
                            new BotProperties.Rag(false));
            var context = new MemoryContext(fixture.db, new MarkdownMemory(fixture.db, props));
            var request =
                    new ContextRequest(
                            new MemoryScope(group, 2, null, 2, now, now),
                            UUID.randomUUID(),
                            0,
                            "A",
                            "继续",
                            QueryMode.CHAT);
            String prompt = context.assemble(request, List::of).userPrompt();
            String line =
                    prompt.lines()
                            .filter(text -> text.startsWith("{\"role\""))
                            .findFirst()
                            .orElseThrow();
            assertTrue(line.startsWith("{\"role\":\"USER\",\"member\":"));
            assertTrue(line.indexOf("\"name\"") < line.indexOf("\"text\""));
        }
    }
}
