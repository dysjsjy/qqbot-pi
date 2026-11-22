package dev.qqbot.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Interactive password setup; raw passwords are never command arguments or saved to disk. */
public final class PasswordHash {
    public static void main(String[] args) throws Exception {
        boolean save = args.length == 2 && args[0].equals("--write-env");
        if (args.length != 0 && !save) {
            throw new IllegalArgumentException("Usage: PasswordHash [--write-env path]");
        }
        var console = System.console();
        if (console == null) {
            throw new IllegalStateException(
                    "Run this command in an interactive PowerShell terminal; a password cannot be read from a redirected pipe.");
        }
        char[] secret = console.readPassword("Administrator password (12+ characters): ");
        char[] confirmation = null;
        try {
            if (secret == null || secret.length < 12) {
                throw new IllegalArgumentException("Use at least 12 characters");
            }
            if (save) {
                confirmation = console.readPassword("Confirm administrator password: ");
                if (!Arrays.equals(secret, confirmation)) {
                    throw new IllegalArgumentException(
                            "Passwords do not match; .env was not changed");
                }
            }
            String hash =
                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12)
                            .encode(new String(secret));
            if (save) {
                writeHash(Path.of(args[1]), hash);
                console.printf(
                        "Administrator password hash saved to .env. Log in with the password you just entered.%n");
            } else {
                console.printf("%s%n", hash);
            }
        } finally {
            if (secret != null) {
                Arrays.fill(secret, '\0');
            }
            if (confirmation != null) {
                Arrays.fill(confirmation, '\0');
            }
        }
    }

    /** Replace only the hash entry, preserving credentials, comments, encoding and line endings. */
    static void writeHash(Path file, String hash) throws IOException {
        if (!hash.matches("\\$2[aby]\\$\\d{2}\\$.{53}")) {
            throw new IllegalArgumentException("Invalid BCrypt hash");
        }
        Path target = file.toAbsolutePath().normalize();
        String text = Files.readString(target, StandardCharsets.UTF_8);
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        var matcher =
                Pattern.compile(
                                "(?m)^([\\t ]*(?:export[\\t ]+)?ADMIN_PASSWORD_HASH[\\t ]*=[\\t ]*)[^\\r\\n]*")
                        .matcher(text);
        var updated = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            matcher.appendReplacement(
                    updated,
                    java.util.regex.Matcher.quoteReplacement(matcher.group(1) + "'" + hash + "'"));
        }
        matcher.appendTail(updated);
        if (!found) {
            if (!text.isEmpty() && !text.endsWith("\n")) {
                updated.append(newline);
            }
            updated.append("ADMIN_PASSWORD_HASH='").append(hash).append("'").append(newline);
        }
        Path temporary = Files.createTempFile(target.getParent(), ".admin-setup-", ".tmp");
        try {
            Files.writeString(temporary, updated, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
