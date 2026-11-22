package dev.qqbot.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PasswordHashTest {
    @TempDir Path directory;
    static final String HASH = "$2a$12$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234";

    @Test
    void savesQuotedHashWithoutChangingOtherSettingsAndHandlesDuplicateEntries() throws Exception {
        Path file = directory.resolve(".env");
        String source =
                "# 中文说明\r\nQQ_APP_SECRET='keep-this-value'\r\nADMIN_PASSWORD_HASH='placeholder'\r\nexport ADMIN_PASSWORD_HASH=other\r\nPI_API_KEY=model-key\r\n";
        Files.writeString(file, source);
        PasswordHash.writeHash(file, HASH);
        String saved = Files.readString(file);
        assertEquals(
                source.replace("'placeholder'", "'" + HASH + "'")
                        .replace("ADMIN_PASSWORD_HASH=other", "ADMIN_PASSWORD_HASH='" + HASH + "'"),
                saved);
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void appendsMissingEntryAndRejectsInvalidHashBeforeWriting() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "QQ_ENABLED=true");
        assertThrows(
                IllegalArgumentException.class, () -> PasswordHash.writeHash(file, "bad-hash"));
        assertEquals("QQ_ENABLED=true", Files.readString(file));
        PasswordHash.writeHash(file, HASH);
        assertEquals(
                "QQ_ENABLED=true\nADMIN_PASSWORD_HASH='" + HASH + "'\n", Files.readString(file));
    }
}
