package dev.qqbot.config;

import io.github.cdimascio.dotenv.Dotenv;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Loads local dotenv settings before Spring selects profiles, below explicit process configuration.
 */
public final class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER - 1;
    }

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        Path file =
                Path.of(environment.getProperty("bot.env-file", ".env"))
                        .toAbsolutePath()
                        .normalize();
        if (!Files.exists(file)) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            var dotenv =
                    Dotenv.configure()
                            .directory(file.getParent().toString())
                            .filename(file.getFileName().toString())
                            .load();
            for (var entry : dotenv.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE)) {
                String value = entry.getValue();
                // dotenv-java removes double quotes; Compose examples also use literal single
                // quotes.
                if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(entry.getKey(), value);
            }
        } catch (RuntimeException failure) {
            // The parser's exception can contain raw lines, including credentials; omit its cause.
            throw new IllegalStateException(
                    "Cannot read dotenv file "
                            + file
                            + ". Check file access and KEY=value syntax; configuration values are omitted.");
        }
        var source = new SystemEnvironmentPropertySource("dotenv", values);
        var sources = environment.getPropertySources();
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
        } else {
            sources.addLast(source);
        }
    }
}
