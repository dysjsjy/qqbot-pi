package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import dev.qqbot.model.*;
import dev.qqbot.pi.PiRuntime;
import dev.qqbot.schedule.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class D03DailyRuntimeTest {
    @TempDir Path directory;

    @Test
    void realPinnedPiUsesDailySystemAnd1000OutputLimitInEveryProviderRequest() throws Exception {
        Path cli =
                Path.of(
                        "runtime/pi/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js");
        assertTrue(Files.isRegularFile(cli));
        var bodies = new CopyOnWriteArrayList<JsonNode>();
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    try {
                        var body = json.readTree(exchange.getRequestBody());
                        bodies.add(body);
                        String prompt =
                                body.path("messages")
                                        .get(1)
                                        .path("content")
                                        .get(0)
                                        .path("text")
                                        .asText();
                        String answer = DailySummaryTestModel.reply(prompt);
                        String chunk =
                                json.writeValueAsString(
                                        Map.of(
                                                "id",
                                                "daily-local",
                                                "object",
                                                "chat.completion.chunk",
                                                "created",
                                                1,
                                                "model",
                                                "test-model",
                                                "choices",
                                                List.of(
                                                        Map.of(
                                                                "index",
                                                                0,
                                                                "delta",
                                                                Map.of(
                                                                        "role",
                                                                        "assistant",
                                                                        "content",
                                                                        answer)))));
                        String end =
                                "{\"id\":\"daily-local\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":40}}";
                        byte[] payload =
                                ("data: " + chunk + "\n\ndata: " + end + "\n\ndata: [DONE]\n\n")
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write(payload);
                    } catch (Exception failure) {
                        exchange.sendResponseHeaders(500, -1);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        var gateway = new BotProperties.Gateway(false, "", "", "", "");
        var props =
                new BotProperties(
                        "admin",
                        "",
                        directory,
                        false,
                        new BotProperties.Pi(
                                "node",
                                cli.toString(),
                                "local-test",
                                "test-model",
                                "DAILY_LOCAL_KEY",
                                "dummy-local-key",
                                20,
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                        null,
                        new BotProperties.Qq(
                                false,
                                new BotProperties.OfficialQq(
                                        "",
                                        "",
                                        "https://api.bot.qq.com",
                                        "https://api.bot.qq.com/app/getAppAccessToken",
                                        false)),
                        gateway,
                        new BotProperties.Rag(false));
        var runtime = new PiRuntime(props, json);
        UUID run = UUID.randomUUID(), group = UUID.randomUUID();
        try {
            var flow =
                    new DailySummaryWorkflow(
                            MemoryProperties.defaults(),
                            System.nanoTime() + TimeUnit.MINUTES.toNanos(2),
                            () -> {},
                            s -> {});
            var result =
                    flow.execute(
                            List.of(
                                    new DailySummaryFacts.SourceMessage(
                                            UUID.randomUUID().toString(),
                                            "time",
                                            "小王",
                                            "待办：小王周四前补齐压测结果")),
                            prompt ->
                                    runtime.generate(
                                                    new GenerationRequest(
                                                            run,
                                                            UUID.randomUUID(),
                                                            group,
                                                            2,
                                                            Purpose.DAILY_SUMMARY,
                                                            PromptPolicy.systemPrompt(
                                                                    Purpose.DAILY_SUMMARY),
                                                            prompt,
                                                            8000,
                                                            1000,
                                                            20),
                                                    new GenerationObserver() {
                                                        public void dispatched() {}

                                                        public void usage(ModelUsage usage) {}
                                                    })
                                            .text());
            assertTrue(result.report().contains("小王周四前补齐压测结果"));
            assertEquals(3, bodies.size());
            for (var body : bodies) {
                assertEquals(
                        1000,
                        body.has("max_completion_tokens")
                                ? body.path("max_completion_tokens").asInt()
                                : body.path("max_tokens").asInt());
                assertEquals(
                        PromptPolicy.systemPrompt(Purpose.DAILY_SUMMARY),
                        body.path("messages").get(0).path("content").asText());
                assertFalse(body.has("tools"));
            }
            Files.createDirectories(Path.of("target/daily-evidence"));
            Files.writeString(
                    Path.of("target/daily-evidence/provider.json"),
                    json.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(
                                    Map.of(
                                            "requests",
                                            bodies.size(),
                                            "outputLimit",
                                            1000,
                                            "systemMatches",
                                            true,
                                            "toolsPresent",
                                            false,
                                            "remoteModelUsed",
                                            false)));
        } finally {
            runtime.close();
            server.stop(0);
        }
    }
}
