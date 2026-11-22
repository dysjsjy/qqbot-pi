package dev.qqbot.config;

import static org.junit.jupiter.api.Assertions.*;

import dev.qqbot.BotApplication;
import dev.qqbot.BotProperties;
import dev.qqbot.persistence.Db;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class DotenvStartupTest {
    @TempDir Path directory;

    @Test
    void defaultConfigurationStartsWithSqliteWithoutAProfileOrEmbeddingConfiguration()
            throws Exception {
        Path file = directory.resolve(".env"), database = directory.resolve("bot.db");
        String hash = new BCryptPasswordEncoder(4).encode("fixture-password");
        Files.writeString(
                file,
                """
            RAG_ENABLED=false
            BOT_SCHEDULING=false
            PORT=0
            PI_PROVIDER=fixture-provider
            PI_MODEL=fixture-model
            PI_API_KEY='fixture#token=with$symbols'
            PI_TIMEOUT_SECONDS=23
            PI_MAX_TIMEOUT_SECONDS=234
            PI_QUEUE_TIMEOUT_SECONDS=87
            PI_REQUEST_TIMEOUT_SECONDS=321
            QQ_ENABLED=false
            WEIXIN_ENABLED=false
            EMBEDDING_URL=
            EMBEDDING_MODEL=
            ADMIN_PASSWORD_HASH='%s'
            DATABASE_URL='jdbc:sqlite:%s'
            """
                        .formatted(hash, database));
        try (var context =
                (ServletWebServerApplicationContext)
                        new SpringApplication(BotApplication.class).run("--bot.env-file=" + file)) {
            var props = context.getBean(BotProperties.class);
            assertEquals(0, context.getEnvironment().getActiveProfiles().length);
            assertFalse(props.ragEnabled());
            assertFalse(props.scheduling());
            assertEquals(hash, props.adminPasswordHash());
            assertEquals("fixture-model", props.pi().model());
            assertEquals("fixture#token=with$symbols", props.pi().apiKey());
            assertEquals(23, props.pi().timeoutSeconds());
            assertEquals(234, props.pi().maxTimeoutSeconds());
            assertEquals(87, props.pi().queueTimeoutSeconds());
            assertEquals(321, props.pi().requestTimeoutSeconds());
            assertNotNull(context.getBean(Db.class));
            assertTrue(Files.isRegularFile(database));
            assertTrue(context.getWebServer().getPort() > 0);
        }
    }
}
