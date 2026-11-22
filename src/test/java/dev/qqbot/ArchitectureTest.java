/**
 * [INPUT]: 读取业务模块、Spring 接线和持久化代码。
 * [OUTPUT]: 验证管理接口、模块依赖和本机部署边界。
 * [POS]: Java 架构回归；业务模块只依赖声明的下层能力。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import dev.qqbot.channel.OfficialQqWebSocket;
import dev.qqbot.channel.OutboxWorker;
import dev.qqbot.delivery.MessageTransport;
import dev.qqbot.message.IngestService;
import dev.qqbot.model.GenerationObserver;
import dev.qqbot.model.GenerationRequest;
import dev.qqbot.model.TextGenerator;
import dev.qqbot.persistence.Db;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

class ArchitectureTest {
    private final Path sources = Path.of("src/main/java/dev/qqbot");

    @Test
    void coreDoesNotIncludeRemovedDatabaseOrPersonalQqIntegrations() throws Exception {
        for (Path path : javaFiles()) {
            String text = Files.readString(path).toLowerCase(Locale.ROOT);
            for (String removed :
                    List.of(
                            "postgres",
                            "pgvector",
                            "onebot",
                            "napcat",
                            "qq_mode",
                            "qq_base_url",
                            "qq_webhook_secret")) {
                assertFalse(text.contains(removed), path + " reintroduces " + removed);
            }
        }
        String dependencies = Files.readString(Path.of("pom.xml")).toLowerCase(Locale.ROOT);
        assertFalse(dependencies.contains("postgresql"));
        assertFalse(dependencies.contains("pglite"));
        Path legacyMigrations = Path.of("src/main/resources/db/migration");
        if (Files.exists(legacyMigrations)) {
            try (var files = Files.list(legacyMigrations)) {
                assertEquals(0, files.count());
            }
        }
        assertEquals(
                Set.of("enabled", "official"),
                Arrays.stream(BotProperties.Qq.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    private List<Path> javaFiles() throws Exception {
        try (var paths = Files.walk(sources)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void httpControllersHaveNoDatabaseOrTransactionResponsibilities() throws Exception {
        for (Path path : javaFiles()) {
            String name =
                    "dev.qqbot."
                            + sources.relativize(path)
                                    .toString()
                                    .replace('\\', '.')
                                    .replace('/', '.')
                                    .replaceAll("\\.java$", "");
            Class<?> type = Class.forName(name, false, getClass().getClassLoader());
            if (!type.isAnnotationPresent(RestController.class)) {
                continue;
            }
            for (var field : type.getDeclaredFields()) {
                assertFalse(
                        Set.of(Db.class, JdbcTemplate.class).contains(field.getType()),
                        type.getName() + " owns persistence");
            }
            for (var method : type.getDeclaredMethods()) {
                assertFalse(
                        method.isAnnotationPresent(Transactional.class),
                        type.getName() + " owns a transaction");
            }
        }
    }

    @Test
    void applicationWorkflowsUseTheModelPortNotPiInfrastructure() throws Exception {
        for (Path path : javaFiles()) {
            String relative = sources.relativize(path).toString().replace('\\', '/');
            if (!relative.matches("(?:message|memory|schedule|management)/.*")) {
                continue;
            }
            assertFalse(
                    Files.readString(path).contains("dev.qqbot.pi.PiRuntime"),
                    path + " depends on the process adapter");
            assertFalse(
                    Files.readString(path)
                            .matches("(?s).*new\\s+(?:MarkdownMemory|MarkdownArchive)\\s*\\(.*"),
                    path + " constructs a memory service");
        }
    }

    @Test
    void modelPort_exposesOnlyGenerationWithAccountingContext() throws Exception {
        var metered =
                TextGenerator.class.getMethod(
                        "generate", GenerationRequest.class, GenerationObserver.class);
        var generationMethods =
                Arrays.stream(TextGenerator.class.getMethods())
                        .filter(method -> method.getName().startsWith("generate"))
                        .toList();

        assertEquals(List.of(metered), generationMethods);
    }

    @Test
    void persistenceDoesNotDependOnApplicationModules() throws Exception {
        try (var paths = Files.list(sources.resolve("persistence"))) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
                assertFalse(
                        Files.readString(path)
                                .matches(
                                        "(?s).*import dev\\.qqbot\\.(?:message|memory|schedule|management|admin|channel)\\..*"),
                        path + " imports an application module");
            }
        }
    }

    @Test
    void ingestionOwnsTheTransactionButNotStorageDetails() throws Exception {
        assertTrue(
                IngestService.class
                        .getMethod("ingest", dev.qqbot.message.GroupIngestRequest.class)
                        .isAnnotationPresent(Transactional.class));
        assertTrue(
                Arrays.stream(IngestService.class.getDeclaredFields())
                        .noneMatch(field -> field.getType() == Db.class));
    }

    @Test
    void deliveryWorkflowUsesTheTransportPortAndGatewayHasNoHttpControllerDependency() {
        assertTrue(
                Arrays.stream(OutboxWorker.class.getDeclaredFields())
                        .anyMatch(field -> field.getType() == MessageTransport.class));
        assertTrue(
                Arrays.stream(OutboxWorker.class.getDeclaredFields())
                        .noneMatch(
                                field ->
                                        Set.of(
                                                        "ChannelSender",
                                                        "OfficialQqClient",
                                                        "WeixinBridgeClient")
                                                .contains(field.getType().getSimpleName())));
        assertTrue(
                Arrays.stream(OfficialQqWebSocket.class.getDeclaredFields())
                        .noneMatch(
                                field ->
                                        field.getType().isAnnotationPresent(RestController.class)));
    }

    @Test
    void modulesDependOnlyOnDeclaredLowerLayers() throws Exception {
        Map<String, Set<String>> allowed =
                Map.ofEntries(
                        Map.entry(
                                "admin",
                                Set.of(
                                        "management",
                                        "memory",
                                        "message",
                                        "schedule",
                                        "usage",
                                        "trace")),
                        Map.entry(
                                "channel",
                                Set.of("message", "delivery", "persistence", "text", "trace")),
                        Map.entry("management", Set.of("memory", "persistence", "text")),
                        Map.entry(
                                "message",
                                Set.of(
                                        "memory",
                                        "schedule",
                                        "model",
                                        "persistence",
                                        "text",
                                        "trace")),
                        Map.entry("memory", Set.of("model", "persistence", "text", "trace")),
                        Map.entry("schedule", Set.of("model", "persistence", "text")),
                        Map.entry("pi", Set.of("model", "trace")),
                        Map.entry("usage", Set.of("model", "persistence", "trace")),
                        Map.entry("trace", Set.of("persistence")),
                        Map.entry("persistence", Set.of()),
                        Map.entry("model", Set.of()),
                        Map.entry("delivery", Set.of()),
                        Map.entry("text", Set.of()),
                        Map.entry("security", Set.of()),
                        Map.entry("config", Set.of()));
        var reference = java.util.regex.Pattern.compile("\\bdev\\.qqbot\\.([a-z]+)\\.");
        for (Path path : javaFiles()) {
            var relative = sources.relativize(path);
            if (relative.getNameCount() < 2) {
                continue;
            }
            String module = relative.getName(0).toString();
            assertTrue(allowed.containsKey(module), "Declare dependencies for module " + module);
            var matcher = reference.matcher(Files.readString(path));
            while (matcher.find()) {
                String dependency = matcher.group(1);
                if (!dependency.equals(module)) {
                    assertTrue(
                            allowed.get(module).contains(dependency),
                            path + " has a forbidden dependency on " + dependency);
                }
            }
        }
    }
}
