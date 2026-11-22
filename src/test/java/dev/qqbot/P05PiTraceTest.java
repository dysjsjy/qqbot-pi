package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.model.*;
import dev.qqbot.pi.PiRuntime;
import dev.qqbot.trace.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P05PiTraceTest {
    @TempDir Path directory;

    @Test
    void phasesCaptureChildResourcesAndTeardownOnFailure() {
        var gateway = new BotProperties.Gateway(false, "", "", "test", "");
        var props =
                new BotProperties(
                        "admin",
                        "",
                        directory,
                        false,
                        new BotProperties.Pi(
                                "node",
                                "src/test/resources/pi-trace-fixture.cjs",
                                "test",
                                "test",
                                "KEY",
                                "",
                                5,
                                ""),
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
        List<SpanRecord> spans = new ArrayList<>();
        UUID run = UUID.randomUUID(), group = UUID.randomUUID();
        var request =
                new GenerationRequest(
                        run,
                        UUID.randomUUID(),
                        group,
                        2,
                        Purpose.CHAT,
                        "system",
                        "question",
                        8000,
                        800,
                        10);
        try (var runtime = new PiRuntimeCloseable(new PiRuntime(props, new ObjectMapper()))) {
            Trace.begin(spans::addAll, Trace.AGENT, "agent.run", run, group);
            try {
                assertEquals(
                        "合成正常答复",
                        runtime.value.generate(request, GenerationObserver.noop()).text());
            } finally {
                Trace.end(Trace.OK, null);
            }
            assertEquals(
                    List.of(
                            "agent.run",
                            "pi.admit",
                            "pi.workspace",
                            "pi.spawn",
                            "pi.generate",
                            "pi.shutdown",
                            "pi.teardown"),
                    spans.stream().map(SpanRecord::name).toList());
            var generation =
                    spans.stream()
                            .filter(span -> span.name().equals("pi.generate"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(1500L, generation.attributes().get("childCpuUs"));
            assertEquals(40000000L, generation.attributes().get("childRssBytes"));
            assertEquals(8000000L, generation.attributes().get("childHeapBytes"));
            assertEquals(2, generation.attributes().get("usageFrames"));
            assertTrue((long) generation.attributes().get("firstTokenMs") >= 0);
            spans.clear();
            Trace.begin(spans::addAll, Trace.AGENT, "agent.run", run, group);
            try {
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                runtime.value.generate(
                                        new GenerationRequest(
                                                run,
                                                UUID.randomUUID(),
                                                group,
                                                2,
                                                Purpose.CHAT,
                                                "system",
                                                "usage-error",
                                                8000,
                                                800,
                                                10),
                                        GenerationObserver.noop()));
            } finally {
                Trace.end(Trace.ERROR, "PI_FAILED");
            }
            assertTrue(spans.stream().anyMatch(span -> span.name().equals("pi.teardown")));
            assertFalse(Trace.active());
        }
    }

    private record PiRuntimeCloseable(PiRuntime value) implements AutoCloseable {
        public void close() {
            value.close();
        }
    }
}
