/**
 * [INPUT]: 使用固定 Pi 运行时、子进程夹具、本地模型和可观察的运行登记表。
 * [OUTPUT]: 核对实际输出上限、计量、并发槽、取消和进程回收。
 * [POS]: 以真实转发路径验证模型端口及 Pi 生命周期。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.pi;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.qqbot.BotProperties;
import dev.qqbot.MemoryProperties;
import dev.qqbot.model.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class T02RuntimeTest {
    @TempDir Path state;
    private final ObjectMapper json = new ObjectMapper();
    private final UUID groupId = UUID.randomUUID();
    private final PiRunRegistry runs = new PiRunRegistry();

    @Test
    void R01RealPinnedPiEnforces137And251InActualProviderRequests() throws Exception {
        Path cli =
                Path.of(
                        "runtime/pi/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js");
        assertTrue(
                Files.isRegularFile(cli),
                "Install the pinned runtime before running the HARD real-pi protocol test");
        var bodies = new CopyOnWriteArrayList<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    bodies.add(json.readTree(exchange.getRequestBody()));
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    String payload =
                            "data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"本地协议验证\"},\"finish_reason\":null}]}\n\ndata: {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":125,\"completion_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":20,\"cache_write_tokens\":5},\"completion_tokens_details\":{\"reasoning_tokens\":3}}}\n\ndata: [DONE]\n\n";
                    exchange.getResponseBody().write(payload.getBytes(StandardCharsets.UTF_8));
                    exchange.close();
                });
        server.start();
        var runtime =
                runtime(
                        cli.toString(),
                        20,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        try {
            for (int limit : List.of(137, 251)) {
                var observer = new Capture();
                var result =
                        runtime.generate(
                                request(
                                        UUID.randomUUID(),
                                        Purpose.CHAT,
                                        "real-cap-" + limit,
                                        limit,
                                        20),
                                observer);
                assertEquals("本地协议验证", result.text());
                assertEquals(
                        new ModelUsage(100L, 10L, 20L, 5L, 3L, UsageStatus.REPORTED),
                        result.usage());
                assertEquals(result.usage(), observer.last());
                assertEquals("dispatched", observer.events.get(0));
            }
            assertEquals(2, bodies.size());
            assertEquals(
                    List.of(137, 251),
                    bodies.stream()
                            .map(
                                    body ->
                                            body.has("max_completion_tokens")
                                                    ? body.get("max_completion_tokens").intValue()
                                                    : body.path("max_tokens").intValue())
                            .toList());
            assertTrue(bodies.stream().noneMatch(body -> body.has("tools")));
            assertEquals(
                    "test-system", bodies.get(0).path("messages").get(0).path("content").asText());
            assertEquals(2, bodies.get(0).path("messages").size());
            String boundaryPrompt = "real-budget-boundary";
            int boundaryBytes =
                    "test-system".getBytes(StandardCharsets.UTF_8).length
                            + boundaryPrompt.getBytes(StandardCharsets.UTF_8).length
                            + 256;
            var exact =
                    new GenerationRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            groupId,
                            2,
                            Purpose.CHAT,
                            "test-system",
                            boundaryPrompt,
                            boundaryBytes,
                            137,
                            20);
            var exactCapture = new Capture();
            assertEquals("本地协议验证", runtime.generate(exact, exactCapture).text());
            assertEquals(3, bodies.size());
            assertEquals(0, exactCapture.dispatched.getCount());
            assertEquals(2, bodies.get(2).path("messages").size());
            var providerUser = bodies.get(2).path("messages").get(1);
            assertEquals("user", providerUser.path("role").asText());
            assertEquals(1, providerUser.path("content").size());
            assertEquals("text", providerUser.path("content").get(0).path("type").asText());
            assertEquals(boundaryPrompt, providerUser.path("content").get(0).path("text").asText());
            var rejected = new Capture();
            var oneBelow =
                    new GenerationRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            groupId,
                            2,
                            Purpose.CHAT,
                            "test-system",
                            boundaryPrompt,
                            boundaryBytes - 1,
                            137,
                            20);
            assertEquals(
                    "CONTEXT_TOO_LARGE",
                    assertThrows(
                                    IllegalStateException.class,
                                    () -> runtime.generate(oneBelow, rejected))
                            .getMessage());
            assertTrue(rejected.events.isEmpty());
            assertEquals(3, bodies.size());
            var windowCapture = new Capture();
            var windowOverflow =
                    new GenerationRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            groupId,
                            2,
                            Purpose.CHAT,
                            "test-system",
                            "x".repeat(32000),
                            32768,
                            800,
                            20);
            assertEquals(
                    "CONTEXT_TOO_LARGE",
                    assertThrows(
                                    IllegalStateException.class,
                                    () -> runtime.generate(windowOverflow, windowCapture))
                            .getMessage());
            assertTrue(windowCapture.events.isEmpty());
            assertEquals(3, bodies.size());
            Path evidence = Path.of("target/memory-v2-runtime-requests.json");
            Files.writeString(
                    evidence, json.writerWithDefaultPrettyPrinter().writeValueAsString(bodies));
        } finally {
            runtime.close();
            server.stop(0);
        }
    }

    @Test
    void R02R03UsageSurvivesErrorAndUsesCumulativeSnapshots() {
        var runtime = runtime(fixture(), 5, "");
        try {
            var capture = new Capture();
            var result =
                    runtime.generate(
                            request(UUID.randomUUID(), Purpose.CHAT, "normal", 137, 5), capture);
            assertEquals(
                    new ModelUsage(100L, 10L, 20L, 5L, 3L, UsageStatus.REPORTED), result.usage());
            assertEquals(result.usage(), capture.last());
            var failed = new Capture();
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            runtime.generate(
                                    request(UUID.randomUUID(), Purpose.CHAT, "usage-error", 137, 5),
                                    failed));
            assertEquals(result.usage(), failed.last());
            assertEquals("dispatched", failed.events.get(0));
        } finally {
            runtime.close();
        }
    }

    @Test
    void R04PartialMissingAndDefaultZeroUsageStayUnknown() {
        var runtime = runtime(fixture(), 5, "");
        try {
            for (String prompt : List.of("no-usage", "zero-usage", "partial-usage")) {
                var result =
                        runtime.generate(
                                request(UUID.randomUUID(), Purpose.CHAT, prompt, 137, 5),
                                new Capture());
                assertEquals(UsageStatus.UNKNOWN, result.usage().status());
                if (prompt.equals("partial-usage")) {
                    assertEquals(100L, result.usage().inputTokens());
                    assertNull(result.usage().cacheWriteTokens());
                }
            }
        } finally {
            runtime.close();
        }
    }

    @Test
    void R05StartupAndBudgetFailureNeverDispatch() {
        var runtime = runtime("missing-pi-entry.cjs", 5, "");
        try {
            var capture = new Capture();
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            runtime.generate(
                                    request(UUID.randomUUID(), Purpose.CHAT, "normal", 137, 5),
                                    capture));
            assertTrue(capture.events.isEmpty());
            var tooLarge =
                    new GenerationRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            groupId,
                            2,
                            Purpose.CHAT,
                            "system",
                            "x".repeat(1000),
                            100,
                            137,
                            5);
            assertEquals(
                    "CONTEXT_TOO_LARGE",
                    assertThrows(
                                    IllegalStateException.class,
                                    () -> runtime.generate(tooLarge, capture))
                            .getMessage());
            assertTrue(capture.events.isEmpty());
        } finally {
            runtime.close();
        }
    }

    @Test
    void R02DuplicateExplicitZeroAndMissingUsageRemainIndependentAcrossCalls() {
        var runtime = runtime(fixture(), 5, "");
        try {
            var full = new ModelUsage(100L, 10L, 20L, 5L, 3L, UsageStatus.REPORTED);
            for (String prompt :
                    List.of(
                            "normal",
                            "duplicate-usage",
                            "zero-cache-write",
                            "partial-usage",
                            "no-usage",
                            "zero-usage")) {
                var capture = new Capture();
                var result =
                        runtime.generate(
                                request(UUID.randomUUID(), Purpose.CHAT, prompt, 137, 5), capture);
                if (prompt.equals("no-usage")) {
                    assertTrue(capture.usage.isEmpty());
                } else {
                    assertEquals(result.usage(), capture.last(), prompt + " observer and Result");
                }
                assertEquals("dispatched", capture.events.get(0));
                switch (prompt) {
                    case "normal", "duplicate-usage" -> assertEquals(full, result.usage(), prompt);
                    case "zero-cache-write" ->
                            assertEquals(
                                    new ModelUsage(100L, 10L, 20L, 0L, 3L, UsageStatus.REPORTED),
                                    result.usage());
                    case "partial-usage" ->
                            assertEquals(
                                    new ModelUsage(100L, 10L, 20L, null, 3L, UsageStatus.UNKNOWN),
                                    result.usage());
                    case "no-usage" -> {
                        assertEquals(UsageStatus.UNKNOWN, result.usage().status());
                        assertNull(result.usage().inputTokens());
                        assertNull(result.usage().outputTokens());
                        assertNull(result.usage().cacheReadTokens());
                        assertNull(result.usage().cacheWriteTokens());
                    }
                    default -> {
                        assertEquals(UsageStatus.UNKNOWN, result.usage().status());
                        assertNotEquals(full, result.usage());
                    }
                }
            }
        } finally {
            runtime.close();
        }
    }

    @Test
    void R06R07BackgroundWaitDoesNotHoldTotalSlotAndWaitingRunCanBeCancelled() throws Exception {
        var runtime = runtime(fixture(), 15, "");
        var executor = Executors.newFixedThreadPool(4);
        UUID first = UUID.randomUUID(),
                waiting = UUID.randomUUID(),
                chat = UUID.randomUUID(),
                extra = UUID.randomUUID();
        var firstCapture = new Capture();
        var waitingCapture = new Capture();
        var chatCapture = new Capture();
        var extraCapture = new Capture();
        Future<?> backgroundCall = null, waitingCall = null, chatCall = null;
        try {
            backgroundCall =
                    executor.submit(
                            () ->
                                    runtime.generate(
                                            request(
                                                    first,
                                                    Purpose.MEMORY_SUMMARY,
                                                    "hold:background",
                                                    137,
                                                    15),
                                            firstCapture));
            assertTrue(firstCapture.dispatched.await(5, TimeUnit.SECONDS));
            waitingCall =
                    executor.submit(
                            () ->
                                    runtime.generate(
                                            request(
                                                    waiting,
                                                    Purpose.DAILY_SUMMARY,
                                                    "hold:waiting",
                                                    137,
                                                    15),
                                            waitingCapture));
            awaitActive(waiting);
            chatCall =
                    executor.submit(
                            () ->
                                    runtime.generate(
                                            request(chat, Purpose.CHAT, "hold:chat", 137, 15),
                                            chatCapture));
            assertTrue(chatCapture.dispatched.await(5, TimeUnit.SECONDS));
            var extraCall =
                    executor.submit(
                            () ->
                                    runtime.generate(
                                            request(extra, Purpose.CHAT, "hold:extra", 137, 15),
                                            extraCapture));
            awaitActive(extra);
            assertFalse(extraCapture.dispatched.await(100, TimeUnit.MILLISECONDS));
            assertEquals(1, waitingCapture.dispatched.getCount());
            assertEquals(0, firstCapture.dispatched.getCount());
            runtime.cancel(waiting);
            assertCancelled(waitingCall);
            assertEquals(1, waitingCapture.dispatched.getCount());
            runtime.cancel(chat);
            assertCancelled(chatCall);
            assertTrue(extraCapture.dispatched.await(5, TimeUnit.SECONDS));
            runtime.cancel(extra);
            assertCancelled(extraCall);
            runtime.cancel(first);
            assertCancelled(backgroundCall);
            assertEquals(
                    "合成正常答复",
                    runtime.generate(
                                    request(
                                            UUID.randomUUID(),
                                            Purpose.MEMORY_SUMMARY,
                                            "normal",
                                            137,
                                            5),
                                    new Capture())
                            .text());
            assertWorkspaceClean();
        } finally {
            runtime.cancel(first);
            runtime.cancel(waiting);
            runtime.cancel(chat);
            runtime.cancel(extra);
            runtime.close();
            executor.shutdownNow();
        }
    }

    @Test
    void R08SuccessTimeoutAndCancellationReleaseResourcesRepeatedly() throws Exception {
        var runtime = runtime(fixture(), 1, "");
        var executor = Executors.newSingleThreadExecutor();
        try {
            for (int attempt = 0; attempt < 20; attempt++) {
                assertEquals(
                        "合成正常答复",
                        runtime.generate(
                                        request(UUID.randomUUID(), Purpose.CHAT, "normal", 137, 2),
                                        new Capture())
                                .text());
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                runtime.generate(
                                        request(
                                                UUID.randomUUID(),
                                                Purpose.MEMORY_SUMMARY,
                                                "slow",
                                                137,
                                                1),
                                        new Capture()));
                UUID runId = UUID.randomUUID();
                var capture = new Capture();
                var cancelled =
                        executor.submit(
                                () ->
                                        runtime.generate(
                                                request(
                                                        runId,
                                                        Purpose.MEMORY_SUMMARY,
                                                        "slow",
                                                        137,
                                                        2),
                                                capture));
                assertTrue(capture.dispatched.await(3, TimeUnit.SECONDS));
                long processId = activeProcess(runId).pid();
                runtime.cancel(runId);
                assertCancelled(cancelled);
                assertFalse(ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false));
                assertWorkspaceClean();
            }
            assertEquals(
                    "合成正常答复",
                    runtime.generate(
                                    request(UUID.randomUUID(), Purpose.CHAT, "normal", 137, 2),
                                    new Capture())
                            .text());
        } finally {
            runtime.close();
            executor.shutdownNow();
        }
    }

    private String fixture() {
        return "src/test/resources/pi-memory-v2-fixture.cjs";
    }

    private GenerationRequest request(
            UUID runId, Purpose purpose, String prompt, int limit, int timeout) {
        return new GenerationRequest(
                runId,
                UUID.randomUUID(),
                groupId,
                2,
                purpose,
                "test-system",
                prompt,
                8000,
                limit,
                timeout);
    }

    private PiRuntime runtime(String cli, int timeout, String url) {
        var gateway = new BotProperties.Gateway(false, "", "", "", "");
        var props =
                new BotProperties(
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
                                "dummy-local-key",
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
                        new BotProperties.Rag(false));
        return new PiRuntime(props, json, MemoryProperties.defaults(), runs);
    }

    private void assertWorkspaceClean() throws Exception {
        if (Files.exists(state)) {
            try (var entries = Files.list(state)) {
                assertTrue(
                        entries.noneMatch(
                                entry -> entry.getFileName().toString().startsWith("run-")));
            }
        }
    }

    private void assertCancelled(Future<?> future) {
        var failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(CancellationException.class, failure.getCause());
    }

    private void awaitActive(UUID runId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (runs.find(runId).isEmpty() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(runs.find(runId).isPresent(), "Missing active Pi run " + runId);
    }

    private Process activeProcess(UUID runId) {
        return runs.find(runId)
                .flatMap(PiRunRegistry.Run::findChild)
                .orElseThrow(() -> new AssertionError("Missing Pi process for " + runId));
    }

    private static class Capture implements GenerationObserver {
        final CountDownLatch dispatched = new CountDownLatch(1);
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<ModelUsage> usage = new CopyOnWriteArrayList<>();

        public void dispatched() {
            events.add("dispatched");
            dispatched.countDown();
        }

        public void usage(ModelUsage value) {
            events.add("usage");
            usage.add(value);
        }

        ModelUsage last() {
            return usage.get(usage.size() - 1);
        }
    }
}
