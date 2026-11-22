/**
 * [INPUT]: 使用本地子进程夹具与固定 Pi 运行时。
 * [OUTPUT]: 验证生成、进度超时、取消及原生会话恢复。
 * [POS]: Pi 运行时的集成回归；本地 HTTP 模型代替远程模型。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.qqbot.pi.PiRuntime;
import dev.qqbot.pi.PiSession;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PiRuntimeTest {
    @TempDir Path state;

    BotProperties props(String cli, int timeout, String url) {
        var gateway = new BotProperties.Gateway(false, "", "", "", "");
        return new BotProperties(
                "admin",
                "",
                state,
                false,
                new BotProperties.Pi(
                        "node",
                        cli,
                        "local-test",
                        "test-model",
                        "TEST_MODEL_KEY",
                        "local-dummy",
                        timeout,
                        url),
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
                new BotProperties.Rag(true));
    }

    @Test
    void generationIgnoresPrivateOutputAndSavesCheckpoint() throws Exception {
        try (var runtime =
                new RuntimeHandle(
                        new PiRuntime(
                                props("src/test/resources/pi-fixture.cjs", 5, ""),
                                new ObjectMapper()))) {
            var result =
                    runtime.pi.generateNative(
                            UUID.randomUUID(), "normal", new PiSession(true, null));
            assertEquals("中文回答🙂", result.text());
            assertEquals("checkpoint", Files.readString(Path.of(result.checkpoint())));
            var restored =
                    runtime.pi.generateNative(
                            UUID.randomUUID(), "normal", new PiSession(true, result.checkpoint()));
            assertEquals(result.text(), restored.text());
        }
    }

    @Test
    void timeoutAndCancellationStopChild() throws Exception {
        var pi =
                new PiRuntime(
                        props("src/test/resources/pi-fixture.cjs", 1, ""), new ObjectMapper());
        try {
            assertThrows(
                    IllegalStateException.class,
                    () -> pi.generateNative(UUID.randomUUID(), "slow", PiSession.ephemeral()));
            UUID id = UUID.randomUUID();
            var work =
                    CompletableFuture.runAsync(
                            () -> pi.generateNative(id, "slow", PiSession.ephemeral()));
            Thread.sleep(300);
            pi.cancel(id);
            assertThrows(ExecutionException.class, () -> work.get(3, TimeUnit.SECONDS));
            assertTrue(
                    Files.list(state)
                            .noneMatch(p -> p.getFileName().toString().startsWith("run-")));
        } finally {
            pi.close();
        }
    }

    @Test
    void liveTextAndThinkingProgressExtendInitialWindowButNeverLeakThinking() {
        var pi =
                new PiRuntime(
                        props("src/test/resources/pi-fixture.cjs", 1, ""), new ObjectMapper());
        try {
            assertEquals(
                    "流式最终回答",
                    pi.generateNative(UUID.randomUUID(), "stream", PiSession.ephemeral(), 5)
                            .text());
            assertEquals(
                    "流式最终回答",
                    pi.generateNative(
                                    UUID.randomUUID(), "thinking-stream", PiSession.ephemeral(), 5)
                            .text());
        } finally {
            pi.close();
        }
    }

    @Test
    void unrelatedEventsStallsAndEndlessStreamsHaveSeparateLimits() {
        var pi =
                new PiRuntime(
                        props("src/test/resources/pi-fixture.cjs", 1, ""), new ObjectMapper());
        try {
            assertEquals(
                    "PI_FIRST_CONTENT_TIMEOUT",
                    assertThrows(
                                    IllegalStateException.class,
                                    () ->
                                            pi.generateNative(
                                                    UUID.randomUUID(),
                                                    "heartbeat",
                                                    PiSession.ephemeral(),
                                                    5))
                            .getMessage());
            assertEquals(
                    "PI_IDLE_TIMEOUT",
                    assertThrows(
                                    IllegalStateException.class,
                                    () ->
                                            pi.generateNative(
                                                    UUID.randomUUID(),
                                                    "stall",
                                                    PiSession.ephemeral(),
                                                    5))
                            .getMessage());
            assertEquals(
                    "PI_DEADLINE",
                    assertThrows(
                                    IllegalStateException.class,
                                    () ->
                                            pi.generateNative(
                                                    UUID.randomUUID(),
                                                    "endless",
                                                    PiSession.ephemeral(),
                                                    2))
                            .getMessage());
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            pi.generateNative(
                                    UUID.randomUUID(), "model-error", PiSession.ephemeral()));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            pi.generateNative(
                                    UUID.randomUUID(), "thinking-only", PiSession.ephemeral()));
        } finally {
            pi.close();
        }
    }

    @Test
    void configuredHardLimitCapsEvenALongerCallerBudget() {
        var base = props("src/test/resources/pi-fixture.cjs", 1, "");
        var settings = base.pi();
        var limited =
                new BotProperties(
                        base.adminUsername(),
                        base.adminPasswordHash(),
                        base.stateDir(),
                        false,
                        new BotProperties.Pi(
                                settings.node(),
                                settings.cli(),
                                settings.provider(),
                                settings.model(),
                                settings.keyEnv(),
                                settings.apiKey(),
                                1,
                                "",
                                2,
                                120,
                                420),
                        null,
                        base.qq(),
                        base.weixin(),
                        base.rag());
        var pi = new PiRuntime(limited, new ObjectMapper());
        try {
            assertEquals(
                    "PI_DEADLINE",
                    assertThrows(
                                    IllegalStateException.class,
                                    () ->
                                            pi.generateNative(
                                                    UUID.randomUUID(),
                                                    "endless",
                                                    PiSession.ephemeral(),
                                                    10))
                            .getMessage());
        } finally {
            pi.close();
        }
    }

    @Test
    void realPinnedPiUsesLocalModelHttpAndRestoresNativeSession() throws Exception {
        Path cli =
                Path.of(
                        "runtime/pi/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js");
        Assumptions.assumeTrue(
                Files.isRegularFile(cli), "Run npm ci in runtime/pi for real pi protocol smoke");
        var requests = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    requests.add(body);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    String payload =
                            "data: "
                                    + "{\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"真实pi本地协议验证🙂\"},\"finish_reason\":null}]}"
                                    + "\n\ndata: {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\ndata: [DONE]\n\n";
                    exchange.getResponseBody().write(payload.getBytes(StandardCharsets.UTF_8));
                    exchange.close();
                });
        server.start();
        var pi =
                new PiRuntime(
                        props(
                                cli.toString(),
                                20,
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                        new ObjectMapper());
        try {
            var first =
                    pi.generateNative(UUID.randomUUID(), "first-marker", new PiSession(true, null));
            assertEquals("真实pi本地协议验证🙂", first.text());
            assertNotNull(first.checkpoint());
            var second =
                    pi.generateNative(
                            UUID.randomUUID(),
                            "second-marker",
                            new PiSession(true, first.checkpoint()));
            assertEquals(first.text(), second.text());
            assertEquals(2, requests.size());
            assertTrue(requests.get(1).contains("first-marker"));
            assertFalse(requests.get(0).contains("tools"));
        } finally {
            pi.close();
            server.stop(0);
        }
    }

    private record RuntimeHandle(PiRuntime pi) implements AutoCloseable {
        public void close() {
            pi.close();
        }
    }
}
