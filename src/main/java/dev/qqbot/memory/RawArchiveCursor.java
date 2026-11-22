/**
 * [INPUT]: 接收 RAW 分页作用域、快照序号和成员指纹。
 * [OUTPUT]: 解码并校验旧版游标；生成兼容的无填充 Base64URL 游标。
 * [POS]: RAW 分页请求和响应的序列化边界；不访问源记录。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.persistence.Db;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class RawArchiveCursor {
    private static final int VERSION = 1;
    private static final int MAX_CURSOR_LENGTH = 4096;
    private static final String INVALID_CURSOR = "RAW_CURSOR_INVALID";

    private final Db db;

    RawArchiveCursor(Db db) {
        this.db = db;
    }

    Optional<Position> decodeCursor(DocumentScope scope, String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        if (token.length() > MAX_CURSOR_LENGTH) {
            throw invalidCursor();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            Map<?, ?> serialized = db.parse(new String(decoded, StandardCharsets.UTF_8), Map.class);
            requireScope(scope, serialized);
            return Optional.of(readPosition(serialized));
        } catch (IllegalArgumentException failure) {
            throw invalidCursor();
        }
    }

    private static void requireScope(DocumentScope scope, Map<?, ?> fields) {
        if (fields == null
                || !Integer.valueOf(VERSION).equals(fields.get("v"))
                || !scope.partition().groupId().toString().equals(fields.get("group"))
                || !scope.documentId().toString().equals(fields.get("document"))
                || scope.partition().epoch() != readNumber(fields, "epoch")) {
            throw invalidCursor();
        }
    }

    private static Position readPosition(Map<?, ?> fields) {
        long after = readNumber(fields, "after");
        long through = readNumber(fields, "through");
        Object members = fields.get("members");
        if (after < 0 || through < after || members == null) {
            throw invalidCursor();
        }
        return new Position(after, through, members.toString());
    }

    private static long readNumber(Map<?, ?> fields, String field) {
        Object value = fields.get(field);
        if (value == null) {
            throw invalidCursor();
        }
        return Long.parseLong(value.toString());
    }

    String encodeCursor(DocumentScope scope, Position position) {
        Map<String, Object> fields =
                Map.of(
                        "v",
                        VERSION,
                        "group",
                        scope.partition().groupId().toString(),
                        "document",
                        scope.documentId().toString(),
                        "epoch",
                        Long.toString(scope.partition().epoch()),
                        "after",
                        Long.toString(position.after()),
                        "through",
                        Long.toString(position.through()),
                        "members",
                        position.members());
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(db.json(fields).getBytes(StandardCharsets.UTF_8));
    }

    static ResponseStatusException invalidCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_CURSOR);
    }

    record DocumentScope(RawPartition partition, UUID documentId) {}

    record Position(long after, long through, String members) {}
}
