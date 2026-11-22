/**
 * [INPUT]: 接收状态根目录、分段标识和待回收的相对路径。
 * [OUTPUT]: 生成分区路径，拒绝越界路径和链接，并执行已校验的回收。
 * [POS]: 分段存储的文件系统边界；调用方负责实例和群锁。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

final class RawSegmentPaths {
    private static final String INVALID_PATH = "RAW_PATH_INVALID";
    private static final String DELETE_FAILED = "RAW_GC_IO";
    private static final int DIGEST_BUFFER_BYTES = 65_536;
    private final Path root;

    RawSegmentPaths(Path stateDir) {
        this.root = stateDir.toAbsolutePath().normalize().resolve("memory");
    }

    Path root() {
        return root;
    }

    Path path(RawSegment segment, String extension) {
        RawPartition partition = segment.partition();
        Path group = root.resolve(partition.groupId().toString());
        Path epoch = group.resolve("raw-log").resolve("epoch-" + partition.epoch());
        String filename =
                String.format(
                        Locale.ROOT,
                        "%020d-g%d.%s",
                        segment.baseSeq(),
                        segment.generation(),
                        extension);
        return safe(epoch.resolve(filename));
    }

    String relative(Path path) {
        Path relative = root.relativize(safe(path));
        return relative.toString().replace('\\', '/');
    }

    Path resolve(String relative) {
        return safe(root.resolve(relative));
    }

    Path safe(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            throw new RawArchiveException(INVALID_PATH);
        }
        try {
            Path current = absolute.getRoot();
            for (Path part : absolute) {
                current = current.resolve(part);
                rejectLink(current);
            }
            return absolute;
        } catch (IOException failure) {
            throw new RawArchiveException(INVALID_PATH, failure);
        }
    }

    private static void rejectLink(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        var attributes =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new RawArchiveException(INVALID_PATH);
        }
    }

    void delete(String relative) {
        Path file = resolve(relative);
        try {
            // SIDE EFFECT: 逐节点复核路径，不跟随链接越过归档根目录。
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                Files.walkFileTree(file, new DeleteTree());
            } else {
                Files.deleteIfExists(file);
            }
        } catch (IOException failure) {
            throw new RawArchiveException(DELETE_FAILED, failure);
        }
    }

    void deleteExpected(String relative, String expectedHash) {
        Path file = resolve(relative);
        if (!Files.exists(file)) {
            return;
        }
        if (!contentHash(file).equals(expectedHash)) {
            throw new RawArchiveException("RAW_MIGRATION_SOURCE_CHANGED");
        }
        delete(relative);
    }

    private static String contentHash(Path file) {
        // SHA-256 覆盖旧文件完整字节，防止回收迁移校验后改变的文件。
        try (var input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[DIGEST_BUFFER_BYTES];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException failure) {
            throw new RawArchiveException(DELETE_FAILED, failure);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private final class DeleteTree extends SimpleFileVisitor<Path> {
        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            safe(directory);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path child, BasicFileAttributes attributes)
                throws IOException {
            Files.deleteIfExists(safe(child));
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                throws IOException {
            if (failure != null) {
                throw failure;
            }
            Files.deleteIfExists(safe(directory));
            return FileVisitResult.CONTINUE;
        }
    }
}
