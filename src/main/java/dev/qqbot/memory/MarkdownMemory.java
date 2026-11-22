/**
 * [INPUT]: 依赖 SQLite 会话与墓碑、状态目录及 Markdown 正文。
 * [OUTPUT]: 读写群记忆和归档文档；删除时登记文件墓碑。
 * [POS]: Markdown 文件边界；不负责摘要生成或向量索引。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.BotProperties;
import dev.qqbot.persistence.Db;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** A human-editable, group-scoped MEMORY.md, independent of vector indexing. */
@Service
public class MarkdownMemory {
    private static final int MAX_MANUAL_LENGTH = 32_000;
    private final Db db;
    private final Path root;

    public MarkdownMemory(Db db, BotProperties properties) {
        this.db = db;
        this.root = properties.stateDir().toAbsolutePath().normalize().resolve("memory");
    }

    private Path path(UUID group) {
        return root.resolve(group.toString()).resolve("MEMORY.md");
    }

    private Path documentPath(UUID group, UUID document, String kind) {
        if (!java.util.Set.of("RAW", "SUMMARY").contains(kind)) {
            throw new IllegalArgumentException("Invalid memory kind");
        }
        return root.resolve(group.toString())
                .resolve(kind.equals("RAW") ? "raw" : "summaries")
                .resolve(document + ".md");
    }

    public String readDocument(UUID group, UUID document, String kind) {
        try {
            Path file = documentPath(group, document, kind);
            return visible(file) && Files.exists(file)
                    ? Files.readString(file, StandardCharsets.UTF_8)
                    : "";
        } catch (IOException failure) {
            throw new IllegalStateException("MEMORY_READ_FAILED", failure);
        }
    }

    public void writeDocument(UUID group, UUID document, String kind, String content) {
        Path file = documentPath(group, document, kind);
        String relativePath = relative(file);
        // SIDE EFFECT: 新文档先落盘，再清除隐藏它的墓碑。
        atomicWrite(file, content);
        db.exec("delete from memory_file_tombstone where relative_path=?", relativePath);
    }

    public void deleteDocument(UUID group, UUID document, String kind) {
        db.tx(
                () -> {
                    queueFiles(group, List.of(documentPath(group, document, kind)));
                    return null;
                });
    }

    private void check(UUID group) {
        if (db.one("select id from group_config where id=?", group) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "群配置不存在");
        }
    }

    public String read(UUID group) {
        check(group);
        try {
            Path file = path(group);
            return visible(file) && Files.exists(file)
                    ? Files.readString(file, StandardCharsets.UTF_8)
                    : "";
        } catch (IOException e) {
            throw new IllegalStateException("MEMORY_READ_FAILED", e);
        }
    }

    @Transactional
    public void write(UUID group, String text) {
        check(group);
        if (text == null || text.length() > MAX_MANUAL_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆内容不能超过 32000 字符");
        }
        Path file = path(group);
        String relativePath = relative(file);
        // SIDE EFFECT: 文件替换不能由 SQLite 回滚；先校验群和路径，再写正文与审计。
        atomicWrite(file, text);
        db.exec("delete from memory_file_tombstone where relative_path=?", relativePath);
        db.audit("MARKDOWN_MEMORY_UPDATE", group);
    }

    private void atomicWrite(Path file, String text) {
        Objects.requireNonNull(text, "Markdown text must not be null");
        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), "memory-", ".tmp");
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            replaceDocument(temp, file);
        } catch (IOException failure) {
            discardTemp(temp, failure);
            throw new IllegalStateException("MEMORY_WRITE_FAILED", failure);
        } catch (RuntimeException failure) {
            discardTemp(temp, failure);
            throw failure;
        }
    }

    private static void replaceDocument(Path temp, Path file) throws IOException {
        try {
            Files.move(
                    temp,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void discardTemp(Path temp, Exception original) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException | SecurityException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    public void delete(UUID group) {
        db.tx(
                () -> {
                    queueFiles(group, List.of(path(group)));
                    for (var row :
                            db.list(
                                    "select id,kind from memory_document where group_id=? and storage='MARKDOWN'",
                                    group)) {
                        queueFiles(
                                group,
                                List.of(
                                        documentPath(
                                                group,
                                                (UUID) row.get("id"),
                                                (String) row.get("kind"))));
                    }
                    var epochs = new HashSet<Long>();
                    for (var row :
                            db.list(
                                    "select distinct epoch from raw_archive_segment where group_id=?",
                                    group)) {
                        epochs.add(((Number) row.get("epoch")).longValue());
                    }
                    for (var row :
                            db.list(
                                    "select epoch,manifest_json from raw_archive_migration where group_id=?",
                                    group)) {
                        epochs.add(((Number) row.get("epoch")).longValue());
                        Map<?, ?> manifest = db.parse((String) row.get("manifest_json"), Map.class);
                        if (manifest.get("oldFiles") instanceof List<?> files) {
                            for (Object item : files) {
                                String relative = ((Map<?, ?>) item).get("path").toString();
                                if (!relative.startsWith(group + "/raw/")
                                        || relative.contains("..")
                                        || relative.indexOf('\\') >= 0) {
                                    throw new RawArchiveException("RAW_PATH_INVALID");
                                }
                                queueFiles(group, List.of(root.resolve(relative)));
                            }
                        }
                    }
                    var current = db.one("select epoch from group_config where id=?", group);
                    if (current != null) {
                        epochs.add(((Number) current.get("epoch")).longValue());
                    }
                    for (long epoch : epochs) {
                        RawArchiveCollector.enqueue(
                                db,
                                group,
                                epoch,
                                "DELETE_PARTITION",
                                Map.of("paths", List.of(group + "/raw-log/epoch-" + epoch)),
                                System.currentTimeMillis());
                    }
                    db.exec(
                            "update raw_archive_migration set state='FAILED',error_code='RAW_SCOPE_CHANGED' where group_id=?",
                            group);
                    return null;
                });
    }

    private String relative(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private boolean visible(Path file) {
        return db.one(
                        "select token from memory_file_tombstone where relative_path=?",
                        relative(file))
                == null;
    }

    private void queueFiles(UUID group, List<Path> paths) {
        var entries = new ArrayList<Map<String, String>>();
        for (Path file : paths) {
            String path = relative(file), token = UUID.randomUUID().toString();
            db.exec(
                    "insert into memory_file_tombstone(relative_path,token) values(?,?) on conflict(relative_path) do update set token=excluded.token",
                    path,
                    token);
            entries.add(Map.of("path", path, "token", token));
        }
        var current = db.one("select epoch from group_config where id=?", group);
        RawArchiveCollector.enqueue(
                db,
                group,
                current == null ? 0 : ((Number) current.get("epoch")).longValue(),
                "DELETE_FILES",
                Map.of("entries", entries),
                System.currentTimeMillis());
    }
}
