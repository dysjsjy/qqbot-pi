package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class T10ConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MemoryProperties.class)
    static class ConfigurationUnderTest {}

    private ApplicationContextRunner application() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ConfigurationUnderTest.class);
    }

    @Test
    void C2MemoryEnvironmentDefaultsLeavePricesUnknown() {
        application()
                .withPropertyValues(
                        "MEMORY_INPUT_TOKENS=8000",
                        "MEMORY_OUTPUT_TOKENS=800",
                        "MEMORY_CONTEXT_WINDOW=32768",
                        "MEMORY_SAFETY_TOKENS=1024",
                        "MEMORY_SUMMARY_OUTPUT_TOKENS=1000",
                        "MEMORY_CURRENCY=USD",
                        "MEMORY_PRICE_VERSION=unset",
                        "MEMORY_INPUT_PRICE=",
                        "MEMORY_OUTPUT_PRICE=",
                        "MEMORY_CACHE_READ_PRICE=",
                        "MEMORY_CACHE_WRITE_PRICE=")
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            assertEquals(
                                    MemoryProperties.defaults(),
                                    context.getBean(MemoryProperties.class));
                        });
    }

    @Test
    void C2MemoryEnvironmentOverridesBindThroughApplicationYaml() {
        application()
                .withPropertyValues(
                        "MEMORY_INPUT_TOKENS=9000",
                        "MEMORY_OUTPUT_TOKENS=900",
                        "MEMORY_CONTEXT_WINDOW=16384",
                        "MEMORY_SAFETY_TOKENS=1536",
                        "MEMORY_SUMMARY_OUTPUT_TOKENS=1200",
                        "MEMORY_CURRENCY=CNY",
                        "MEMORY_PRICE_VERSION=fixture-v1",
                        "MEMORY_INPUT_PRICE=1.5",
                        "MEMORY_OUTPUT_PRICE=2.5",
                        "MEMORY_CACHE_READ_PRICE=0.1",
                        "MEMORY_CACHE_WRITE_PRICE=1.25")
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            var actual = context.getBean(MemoryProperties.class);
                            assertEquals(9000, actual.inputTokens());
                            assertEquals(900, actual.outputTokens());
                            assertEquals(16384, actual.contextWindow());
                            assertEquals(1536, actual.safetyTokens());
                            assertEquals(1200, actual.summaryOutputTokens());
                            assertEquals("CNY", actual.currency());
                            assertEquals("fixture-v1", actual.priceVersion());
                            assertEquals(new BigDecimal("1.5"), actual.inputPrice());
                            assertEquals(new BigDecimal("2.5"), actual.outputPrice());
                            assertEquals(new BigDecimal("0.1"), actual.cacheReadPrice());
                            assertEquals(new BigDecimal("1.25"), actual.cacheWritePrice());
                        });
    }

    @Test
    void C2InvalidEnvironmentBudgetFailsBeforeApplicationCanCallModels() {
        application()
                .withPropertyValues(
                        "MEMORY_INPUT_TOKENS=9000",
                        "MEMORY_OUTPUT_TOKENS=900",
                        "MEMORY_CONTEXT_WINDOW=11735",
                        "MEMORY_SAFETY_TOKENS=1536",
                        "MEMORY_SUMMARY_OUTPUT_TOKENS=1200")
                .run(
                        context -> {
                            Throwable failure = context.getStartupFailure();
                            assertNotNull(failure);
                            while (failure.getCause() != null) {
                                failure = failure.getCause();
                            }
                            assertEquals("INVALID_MODEL_BUDGET", failure.getMessage());
                        });
    }

    @Test
    void C2NegativeEnvironmentPriceFailsBeforeApplicationCanCallModels() {
        application()
                .withPropertyValues(
                        "MEMORY_INPUT_TOKENS=9000",
                        "MEMORY_OUTPUT_TOKENS=900",
                        "MEMORY_CONTEXT_WINDOW=16384",
                        "MEMORY_SAFETY_TOKENS=1536",
                        "MEMORY_SUMMARY_OUTPUT_TOKENS=1200",
                        "MEMORY_INPUT_PRICE=-0.1")
                .run(
                        context -> {
                            Throwable failure = context.getStartupFailure();
                            assertNotNull(failure);
                            while (failure.getCause() != null) {
                                failure = failure.getCause();
                            }
                            assertEquals("INVALID_MODEL_PRICE", failure.getMessage());
                        });
    }
}
