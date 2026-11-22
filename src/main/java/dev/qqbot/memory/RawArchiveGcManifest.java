/**
 * [INPUT]: 接收回收任务的操作、群 ID 与原有 JSON 清单。
 * [OUTPUT]: 提供已校验范围的删除清单或分段重写边界。
 * [POS]: 回收任务的序列化边界。清单中的全部路径在文件操作前校验。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

sealed interface RawArchiveGcManifest {
    String REWRITE_EXPIRED = "REWRITE_EXPIRED";
    String DELETE_FILES = "DELETE_FILES";
    String PATHS = "paths";
    String PATH = "path";
    String INVALID_MANIFEST = "RAW_GC_MANIFEST_INVALID";

    static RawArchiveGcManifest decodeManifest(UUID group, String operation, Map<?, ?> fields) {
        if (REWRITE_EXPIRED.equals(operation)) {
            return Rewrite.read(fields);
        }
        var deletions = new ArrayList<Deletion>();
        if (fields.get("entries") instanceof List<?> entries) {
            for (Object entry : entries) {
                deletions.add(readTombstone(group, requireEntry(entry)));
            }
        } else if (fields.get("hashedPaths") instanceof List<?> files) {
            for (Object file : files) {
                deletions.add(readHashedFile(group, requireEntry(file)));
            }
        } else if (fields.get(PATHS) instanceof List<?> paths) {
            for (Object path : paths) {
                deletions.add(new FilePath(requireGroupPath(group, path)));
            }
        } else {
            throw new RawArchiveException(INVALID_MANIFEST);
        }
        return new DeletePaths(deletions);
    }

    private static Tombstone readTombstone(UUID group, Map<?, ?> entry) {
        String path = requireGroupPath(group, entry.get(PATH));
        return new Tombstone(path, requireText(entry.get("token")));
    }

    private static HashedFile readHashedFile(UUID group, Map<?, ?> file) {
        String path = requireGroupPath(group, file.get(PATH));
        return new HashedFile(path, requireText(file.get("sha256")));
    }

    private static String requireGroupPath(UUID group, Object value) {
        String path = requireText(value);
        if (!path.startsWith(group + "/") || path.indexOf('\\') >= 0 || path.contains("..")) {
            throw new RawArchiveException("RAW_PATH_INVALID");
        }
        return path;
    }

    private static Map<?, ?> requireEntry(Object entry) {
        if (entry instanceof Map<?, ?> fields) {
            return fields;
        }
        throw new RawArchiveException(INVALID_MANIFEST);
    }

    private static String requireText(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new RawArchiveException(INVALID_MANIFEST);
    }

    private static long requireLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new RawArchiveException(INVALID_MANIFEST);
    }

    record Rewrite(UUID segmentId, long generation, long cutoff) implements RawArchiveGcManifest {
        private static Rewrite read(Map<?, ?> fields) {
            return new Rewrite(
                    UUID.fromString(requireText(fields.get("segmentId"))),
                    requireLong(fields.get("generation")),
                    requireLong(fields.get("cutoff")));
        }
    }

    record DeletePaths(List<Deletion> entries) implements RawArchiveGcManifest {
        public DeletePaths {
            entries = List.copyOf(entries);
        }
    }

    sealed interface Deletion permits FilePath, Tombstone, HashedFile {
        String path();
    }

    record FilePath(String path) implements Deletion {}

    record Tombstone(String path, String token) implements Deletion {}

    record HashedFile(String path, String sha256) implements Deletion {}
}
