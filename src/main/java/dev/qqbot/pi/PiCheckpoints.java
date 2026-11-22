/**
 * [INPUT]: 接收状态根目录、请求中的旧检查点和进程报告的新会话路径。
 * [OUTPUT]: 校验路径、准备隔离的会话副本并原子发布完成的检查点。
 * [POS]: Pi 原生会话文件边界；不会覆盖启动所用的历史检查点。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.pi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

final class PiCheckpoints {
    private static final String SESSION_EXTENSION = ".jsonl";
    private final Path directory;

    PiCheckpoints(Path stateRoot) {
        this.directory = stateRoot.resolve("checkpoints");
    }

    Optional<Path> findPrevious(PiSession session) {
        String previous = session.previousCheckpoint();
        if (!session.isPersistent() || previous == null || previous.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(requireFileUnder(previous, directory, "CHECKPOINT_INVALID"));
    }

    void restore(Path previous, Path restored) throws IOException {
        // SIDE EFFECT: pi 写入的是副本，后续运行不能修改过去的检查点。
        Files.copy(previous, restored);
    }

    String publish(UUID runId, Path workspace, String sessionFile) throws IOException {
        if (sessionFile == null) {
            throw new IllegalStateException("SESSION_MISSING");
        }
        Path session = requireFileUnder(sessionFile, workspace, "SESSION_INVALID");
        Path destination = directory.resolve(runId + SESSION_EXTENSION);
        // SIDE EFFECT: 完整写入同一文件系统中的临时文件，再原子发布。
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "checkpoint-", ".tmp");
        try {
            Files.copy(session, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failure) {
            deletePartialCheckpoint(temporary, failure);
            throw failure;
        }
        return destination.toString();
    }

    private static Path requireFileUnder(String rawPath, Path parent, String errorCode) {
        Path source = Path.of(rawPath).toAbsolutePath().normalize();
        if (!source.startsWith(parent) || !Files.isRegularFile(source)) {
            throw new IllegalStateException(errorCode);
        }
        return source;
    }

    private static void deletePartialCheckpoint(Path temporary, Throwable publicationFailure) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException | RuntimeException cleanupFailure) {
            publicationFailure.addSuppressed(cleanupFailure);
        }
    }
}
