package dev.qqbot.pi;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessResourcesTest {
    @TempDir Path root;

    @Test
    void stopWaitsForExitBeforeDeletingTheWorkingDirectory() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("run-process"));
        var process =
                new ProcessBuilder(
                                "node",
                                "-e",
                                "process.on('SIGTERM',()=>{});console.log('ready');setInterval(()=>{},1000)")
                        .directory(workspace.toFile())
                        .start();
        try {
            assertEquals("ready", process.inputReader().readLine());
            ProcessResources.stop(process);
            assertFalse(process.isAlive());
            ProcessResources.deleteWorkspace(workspace);
            assertFalse(Files.exists(workspace));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void interruptedCleanupStillStopsTheChildAndRestoresInterruption() throws Exception {
        var process = new ProcessBuilder("node", "-e", "setInterval(()=>{},1000)").start();
        try {
            Thread.currentThread().interrupt();
            ProcessResources.stop(process);
            ProcessResources.deleteWorkspace(
                    Files.createDirectory(root.resolve("run-interrupted")));
            assertFalse(process.isAlive());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void deletionIsRecursiveAndIdempotent() throws Exception {
        Path workspace =
                Files.createDirectories(root.resolve("run-tree/agent/nested"))
                        .getParent()
                        .getParent();
        Files.writeString(workspace.resolve("agent/nested/settings.json"), "{}");
        ProcessResources.deleteWorkspace(workspace);
        ProcessResources.deleteWorkspace(workspace);
        assertFalse(Files.exists(workspace));
        assertTrue(Files.isDirectory(root));
    }
}
