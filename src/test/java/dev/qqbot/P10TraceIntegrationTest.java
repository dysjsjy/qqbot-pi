package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.channel.OutboxWorker;
import dev.qqbot.delivery.MessageTransport;
import dev.qqbot.memory.*;
import dev.qqbot.message.*;
import dev.qqbot.model.*;
import dev.qqbot.pi.PiRuntime;
import dev.qqbot.trace.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class P10TraceIntegrationTest {
    @TempDir Path directory;
    private static final Instant NOW = T10StartupTest.NOW;

    @TestConfiguration
    static class Transport {
        @Bean
        @Primary
        MessageTransport traceTransport() {
            return message ->
                    new MessageTransport.Delivery(
                            MessageTransport.State.ACCEPTED, "synthetic-delivery", null);
        }
    }

    @Test
    void fullChainHttpPrivacyBackgroundClearAndDisabledOnSqlite() throws Exception {
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("trace.db"))) {
            exercise(fixture, true);
            exercise(fixture, false);
        }
    }

    private void exercise(MemoryV2TestDatabase fixture, boolean enabled) throws Exception {
        exercise(fixture, enabled, null);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TRACE_LIVE_TEST", matches = "true")
    void liveModelFiveSyntheticChats() throws Exception {
        var questions =
                List.of(
                        "你好，简单介绍一下你能提供什么帮助。",
                        "请根据给出的历史资料说明发布前的备份步骤。",
                        "请给一个 Java 判断字符串是否为空的简短示例。",
                        "请为一个小型群聊机器人给出发布检查建议。" + "需要考虑数据库备份、迁移和回滚验证。".repeat(8),
                        "/memory 发布前约定的备份步骤是什么？");
        int first = Integer.parseInt(System.getenv().getOrDefault("TRACE_LIVE_FROM", "1"));
        assertTrue(first >= 1 && first <= questions.size());
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("live.db"))) {
            var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
            for (int index = first - 1; index < questions.size(); index++) {
                String question = questions.get(index);
                checks.add(() -> exercise(fixture, true, question));
            }
            assertAll(checks);
        }
    }

    private void exercise(MemoryV2TestDatabase fixture, boolean enabled, String liveQuestion)
            throws Exception {
        String label =
                "sqlite"
                        + "-"
                        + enabled
                        + (liveQuestion == null ? "" : "-live-" + UUID.randomUUID());
        var gateway = new BotProperties.Gateway(false, "", "", "test", "");
        BotProperties.Pi pi =
                new BotProperties.Pi(
                        "node",
                        "src/test/resources/pi-trace-fixture.cjs",
                        "test",
                        "test",
                        "KEY",
                        "",
                        5,
                        "");
        if (liveQuestion != null) {
            var env = io.github.cdimascio.dotenv.Dotenv.configure().load();
            pi =
                    new BotProperties.Pi(
                            env.get("PI_NODE", "node"),
                            "runtime/pi/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js",
                            env.get("PI_PROVIDER"),
                            env.get("PI_MODEL"),
                            env.get("PI_KEY_ENV", "OPENAI_API_KEY"),
                            env.get("PI_API_KEY", ""),
                            60,
                            env.get("PI_BASE_URL", ""));
        }
        var props =
                new BotProperties(
                        "admin",
                        "",
                        directory.resolve(label),
                        false,
                        pi,
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
        var delegate = new PiRuntime(props, new ObjectMapper());
        var builder =
                new SpringApplicationBuilder(
                        BotApplication.class, T10StartupTest.FixedTime.class, Transport.class);
        builder.initializers(
                context -> {
                    context.getEnvironment()
                            .getPropertySources()
                            .addFirst(
                                    new org.springframework.core.env.MapPropertySource(
                                            "trace-test-gateway",
                                            Map.of("bot.qq.token", "synthetic-test-token")));
                    TextGenerator generator =
                            liveQuestion == null
                                    ? delegate
                                    : (TextGenerator)
                                            java.lang.reflect.Proxy.newProxyInstance(
                                                    TextGenerator.class.getClassLoader(),
                                                    new Class<?>[] {TextGenerator.class},
                                                    (proxy, method, arguments) -> {
                                                        try {
                                                            return method.invoke(
                                                                    delegate, arguments);
                                                        } catch (
                                                                java.lang.reflect
                                                                                .InvocationTargetException
                                                                        failure) {
                                                            for (Throwable cause =
                                                                            failure.getCause();
                                                                    cause != null;
                                                                    cause = cause.getCause()) {
                                                                String code = cause.getMessage();
                                                                System.out.println(
                                                                        "P10_LIVE_FAILURE type="
                                                                                + cause.getClass()
                                                                                        .getSimpleName()
                                                                                + " code="
                                                                                + (code != null
                                                                                                && code
                                                                                                        .matches(
                                                                                                                "[A-Z][A-Z0-9_]{0,79}")
                                                                                        ? code
                                                                                        : "REDACTED"));
                                                            }
                                                            throw failure.getCause();
                                                        }
                                                    });
                    context.addBeanFactoryPostProcessor(
                            factory -> factory.registerSingleton("piTextGenerator", generator));
                });
        try (var app =
                builder.run(
                        "--spring.profiles.active=" + ("sqlite"),
                        "--spring.datasource.url=" + fixture.source.getUrl(),
                        "--spring.datasource.password=",
                        "--spring.datasource.hikari.maximum-pool-size=1",
                        "--server.port=0",
                        "--bot.env-file=" + directory.resolve("no-env/.env"),
                        "--bot.state-dir=" + directory.resolve(label),
                        "--bot.scheduling=false",
                        "--bot.rag.enabled=false",
                        "--bot.trace.enabled=" + enabled,
                        "--bot.qq.enabled=true",
                        "--bot.qq.official.app-id=test",
                        "--bot.qq.official.app-secret=synthetic",
                        "--bot.qq.official.transport=webhook",
                        "--bot.qq.base-url=http://127.0.0.1:1",
                        "--bot.weixin.enabled=false",
                        "--bot.pi.provider=" + pi.provider(),
                        "--bot.pi.model=" + pi.model(),
                        "--bot.admin-password-hash=$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234",
                        "--logging.level.root=WARN",
                        "--spring.main.banner-mode=off")) {
            var db = app.getBean(dev.qqbot.persistence.Db.class);
            var store = app.getBean(TraceStore.class);
            var queries = app.getBean(TraceQueries.class);
            var mvc =
                    MockMvcBuilders.webAppContextSetup((WebApplicationContext) app)
                            .apply(springSecurity())
                            .build();
            mvc.perform(get("/api/admin/traces")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/traces?kind=BAD").with(user("admin").roles("ADMIN")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Unsupported kind"));
            mvc.perform(
                            get("/api/admin/traces/" + UUID.randomUUID())
                                    .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isNotFound());
            UUID group = fixture.group(label);
            String question = liveQuestion == null ? "PRIVATE_TRACE_BODY_" + label : liveQuestion;
            if (liveQuestion != null) {
                fixture.message(
                        group,
                        "historical-source",
                        "A",
                        "项目约定：发布前先备份数据库，再执行迁移，最后验证回滚。",
                        1,
                        NOW.minusSeconds(60));
            }
            var receipt =
                    app.getBean(IngestService.class)
                            .ingest(
                                    new GroupIngestRequest(
                                            "QQ",
                                            "test",
                                            label,
                                            "question",
                                            "A",
                                            "甲",
                                            NOW,
                                            question,
                                            true,
                                            false));
            assertTrue(receipt.accepted());
            UUID run = (UUID) db.one("select id from agent_run where group_id=?", group).get("id");
            if (liveQuestion != null) {
                app.getBean(MemorySearchWorker.class).tick();
            }
            await(
                    () -> {
                        app.getBean(AgentWorker.class).tick();
                        String state =
                                (String)
                                        db.one("select state from agent_run where id=?", run)
                                                .get("state");
                        if (Set.of("FAILED", "CANCELLED", "TIMED_OUT").contains(state)) {
                            fail("Trace run terminated: " + state);
                        }
                        return "SUCCEEDED".equals(state);
                    });
            await(
                    () -> {
                        app.getBean(OutboxWorker.class).tick();
                        return "SENT"
                                .equals(
                                        db.one("select state from outbox where agent_run_id=?", run)
                                                .get("state"));
                    });
            if (!enabled) {
                store.flush();
                assertTrue(queries.list(group, null, 100, 0).isEmpty());
                assertNotNull(
                        db.one(
                                "select id from model_call where run_id=? and state='SUCCEEDED'",
                                run));
                app.getBean(MemoryMaintenance.class).clear(group);
                return;
            }
            await(
                    () -> {
                        store.flush();
                        return queries.list(group, "AGENT", 50, 0).size() == 1;
                    });
            var detail = queries.detail(run);
            var spans = detail.spans();
            assertTrue(
                    spans.stream()
                            .map(SpanRecord::name)
                            .toList()
                            .containsAll(
                                    List.of(
                                            "ingress",
                                            "ingress.enqueue",
                                            "agent.run",
                                            "memory.assemble",
                                            "memory.context",
                                            "context.render",
                                            "context.history",
                                            "agent.stats",
                                            "model.call",
                                            "pi.spawn",
                                            "pi.generate",
                                            "agent.complete",
                                            "delivery.send",
                                            "delivery.transport")));
            assertEquals(
                    List.of("INGRESS", "AGENT", "DELIVERY"),
                    spans.stream()
                            .filter(span -> span.parentSpan() == null)
                            .map(SpanRecord::kind)
                            .toList());
            var model =
                    spans.stream()
                            .filter(span -> span.name().equals("model.call"))
                            .findFirst()
                            .orElseThrow();
            var ledger = db.one("select * from model_call where run_id=?", run);
            String[] columns = {
                "estimated_input_tokens",
                "input_tokens",
                "output_tokens",
                "cache_read_tokens",
                "cache_write_tokens",
                "reasoning_tokens"
            };
            var root =
                    spans.stream()
                            .filter(span -> span.name().equals("agent.run"))
                            .findFirst()
                            .orElseThrow();
            var ledgerTokens = Arrays.stream(columns).map(ledger::get).toList();
            assertEquals(ledgerTokens, usageTokens(model));
            assertEquals(usageTokens(model), usageTokens(root));
            for (var span : spans) {
                assertEquals(run, span.runId());
                assertFalse(db.json(span.attributes()).contains(question));
            }
            assertTrue(root.attributes().containsKey("queueWaitMs"));
            mvc.perform(get("/api/admin/traces/" + run).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.runId").value(run.toString()))
                    .andExpect(jsonPath("$.spans").isArray());
            mvc.perform(get("/api/admin/traces/stats?hours=24").with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].p95Us").isNumber());
            if (liveQuestion != null) {
                Path result = Path.of("target/p12-live-details.json");
                var json = new ObjectMapper();
                List<Object> details = new ArrayList<>();
                if (Files.exists(result)) {
                    details.addAll(json.readValue(Files.readString(result), List.class));
                }
                details.add(detail);
                Files.writeString(result, json.writeValueAsString(details));
                var generation =
                        spans.stream()
                                .filter(span -> span.name().equals("pi.generate"))
                                .findFirst()
                                .orElseThrow();
                assertTrue(((Number) generation.attributes().get("childCpuUs")).longValue() > 0);
                assertTrue(((Number) generation.attributes().get("childRssBytes")).longValue() > 0);
                String answer =
                        (String)
                                db.one("select final_text from agent_run where id=?", run)
                                        .get("final_text");
                assertFalse(answer.contains("**"));
                assertFalse(answer.matches("(?sm).*^#{1,6} .*"));
                System.out.println(
                        "P10_LIVE_TRACE run="
                                + run
                                + " spans="
                                + spans.size()
                                + " usage="
                                + model.inputTokens()
                                + "/"
                                + model.cacheReadTokens());
                app.getBean(MemoryMaintenance.class).clear(group);
                return;
            }
            UUID summaryGroup = fixture.group(label + "-summary");
            app.getBean(MemoryPolicyService.class)
                    .updateSettings(summaryGroup, 0, "SUMMARY", 1, 6000, 30);
            fixture.db.tx(
                    () -> {
                        for (int index = 1; index <= 100; index++) {
                            fixture.message(
                                    summaryGroup,
                                    "source-" + index,
                                    "A",
                                    "合成摘要资料 " + index,
                                    index,
                                    NOW.minusSeconds(120 - index));
                        }
                        return null;
                    });
            await(
                    () -> {
                        app.getBean(MarkdownMemoryWorker.class).tick();
                        store.flush();
                        return !queries.list(summaryGroup, "BACKGROUND", 50, 0).isEmpty();
                    });
            assertEquals(
                    "background.memory_summary",
                    queries.list(summaryGroup, "BACKGROUND", 50, 0).get(0).name());
            app.getBean(MemoryMaintenance.class).clear(group);
            assertTrue(queries.list(group, null, 100, 0).isEmpty());
            assertFalse(queries.list(summaryGroup, null, 100, 0).isEmpty());
            System.out.println(
                    "P10_TRACE_CHAIN "
                            + label
                            + " spans="
                            + spans.size()
                            + " tokens=ledger-matched auth=verified clear=verified");
        } finally {
            delegate.close();
        }
    }

    private static List<Long> usageTokens(SpanRecord span) {
        return Arrays.asList(
                span.estimatedInputTokens(),
                span.inputTokens(),
                span.outputTokens(),
                span.cacheReadTokens(),
                span.cacheWriteTokens(),
                span.reasoningTokens());
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Trace workflow timed out");
            }
            Thread.sleep(15);
        }
    }
}
