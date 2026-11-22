package dev.qqbot.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.*;
import org.springframework.mock.env.MockEnvironment;

class DotenvEnvironmentPostProcessorTest {
    @TempDir Path directory;
    final DotenvEnvironmentPostProcessor loader = new DotenvEnvironmentPostProcessor();

    MockEnvironment environment(Path file) {
        return new MockEnvironment().withProperty("bot.env-file", file.toString());
    }

    @Test
    void missingFileLeavesExistingConfigurationAlone() {
        var env = environment(directory.resolve(".env")).withProperty("PI_MODEL", "existing");
        assertDoesNotThrow(() -> loader.postProcessEnvironment(env, null));
        assertEquals("existing", env.getProperty("PI_MODEL"));
        assertFalse(env.getPropertySources().contains("dotenv"));
    }

    @Test
    void parsesQuotesCommentsEmptyValuesAndLiteralCredentials() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(
                file,
                """
            # 本地模型配置
            SPRING_PROFILES_ACTIVE=sqlite
            RAG_ENABLED=false
            ADMIN_PASSWORD_HASH='$2a$12$literal-dollar-signs'
            PI_API_KEY='fixture#token=with$symbols'
            PI_MODEL="模型名称"
            PI_PROVIDER=custom-llm # inline comment
            EMBEDDING_URL=
            """);
        var env = environment(file);
        loader.postProcessEnvironment(env, null);
        assertEquals("sqlite", env.getProperty("spring.profiles.active"));
        assertEquals("false", env.getProperty("RAG_ENABLED"));
        assertEquals("$2a$12$literal-dollar-signs", env.getProperty("ADMIN_PASSWORD_HASH"));
        assertEquals("fixture#token=with$symbols", env.getProperty("PI_API_KEY"));
        assertEquals("模型名称", env.getProperty("PI_MODEL"));
        assertEquals("custom-llm", env.getProperty("PI_PROVIDER"));
        assertEquals("", env.getProperty("EMBEDDING_URL"));
    }

    @Test
    void processEnvironmentAndLaunchArgumentsOverrideDotenv() throws Exception {
        Path file = directory.resolve("custom.env");
        Files.writeString(file, "PI_MODEL=file-model\nSPRING_PROFILES_ACTIVE=sqlite\n");
        var env = environment(file);
        env.getPropertySources()
                .addFirst(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("PI_MODEL", "process-model")));
        env.getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                                Map.of("PI_MODEL", "system-property-model")));
        env.getPropertySources()
                .addFirst(new SimpleCommandLinePropertySource("--PI_MODEL=cli-model"));
        loader.postProcessEnvironment(env, null);
        assertEquals("cli-model", env.getProperty("PI_MODEL"));
        env.getPropertySources().remove("commandLineArgs");
        assertEquals("system-property-model", env.getProperty("PI_MODEL"));
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        assertEquals("process-model", env.getProperty("PI_MODEL"));
        env.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        assertEquals("file-model", env.getProperty("PI_MODEL"));
        assertEquals("sqlite", env.getProperty("spring.profiles.active"));
    }

    @Test
    void malformedFileFailsWithoutPrintingItsContents() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "invalid line with fixture-sensitive-token\n");
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> loader.postProcessEnvironment(environment(file), null));
        var trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains("fixture-sensitive-token"));
        assertNull(failure.getCause());
    }
}
