/**
 * [INPUT]: 提供已持久化回收清单的字段与预期群 ID。
 * [OUTPUT]: 验证旧清单类型、范围限制和退避边界。
 * [POS]: 回收任务解码与调度的纯逻辑测试。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static dev.qqbot.memory.RawArchiveGcManifest.DELETE_FILES;
import static dev.qqbot.memory.RawArchiveGcManifest.PATHS;
import static dev.qqbot.memory.RawArchiveGcManifest.REWRITE_EXPIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.qqbot.memory.RawArchiveGcManifest.DeletePaths;
import dev.qqbot.memory.RawArchiveGcManifest.FilePath;
import dev.qqbot.memory.RawArchiveGcManifest.HashedFile;
import dev.qqbot.memory.RawArchiveGcManifest.Rewrite;
import dev.qqbot.memory.RawArchiveGcManifest.Tombstone;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RawArchiveGcManifestTest {
    private static final UUID GROUP = UUID.randomUUID();
    private static final String MEMORY_PATH = GROUP + "/MEMORY.md";
    private static final String PATH = "path";

    @Test
    void decodeManifest_preservesExistingDeleteShapes() {
        var tombstone =
                decodeDelete(
                        Map.of("entries", List.of(Map.of(PATH, MEMORY_PATH, "token", "version"))));
        var hashed =
                decodeDelete(
                        Map.of(
                                "hashedPaths",
                                List.of(Map.of(PATH, MEMORY_PATH, "sha256", "digest"))));
        var pathOnly = decodeDelete(Map.of(PATHS, List.of(MEMORY_PATH)));

        assertThat(tombstone)
                .isEqualTo(new DeletePaths(List.of(new Tombstone(MEMORY_PATH, "version"))));
        assertThat(hashed)
                .isEqualTo(new DeletePaths(List.of(new HashedFile(MEMORY_PATH, "digest"))));
        assertThat(pathOnly).isEqualTo(new DeletePaths(List.of(new FilePath(MEMORY_PATH))));
    }

    @Test
    void decodeManifest_rejectsOutOfGroupPathEvenAfterValidEntry() {
        var fields = Map.of(PATHS, List.of(MEMORY_PATH, UUID.randomUUID() + "/MEMORY.md"));
        assertThatThrownBy(() -> decodeDelete(fields))
                .isInstanceOf(RawArchiveException.class)
                .hasMessage("RAW_PATH_INVALID");
    }

    @Test
    void decodeManifest_rejectsMissingTombstoneToken() {
        var fields = Map.of("entries", List.of(Map.of(PATH, MEMORY_PATH)));
        assertThatThrownBy(() -> decodeDelete(fields))
                .isInstanceOf(RawArchiveException.class)
                .hasMessage("RAW_GC_MANIFEST_INVALID");
    }

    @Test
    void decodeManifest_readsLegacyRewriteFields() {
        UUID segment = UUID.randomUUID();
        var fields =
                Map.of(
                        "segmentId",
                        segment.toString(),
                        "generation",
                        3,
                        "cutoff",
                        1_795_334_400_000L);
        var manifest = RawArchiveGcManifest.decodeManifest(GROUP, REWRITE_EXPIRED, fields);

        assertThat(manifest).isEqualTo(new Rewrite(segment, 3, 1_795_334_400_000L));
    }

    @Test
    void retryDelayMs_doublesUntilSixtySeconds() {
        assertThat(RawArchiveCollector.retryDelayMs(0)).isEqualTo(1_000);
        assertThat(RawArchiveCollector.retryDelayMs(5)).isEqualTo(32_000);
        assertThat(RawArchiveCollector.retryDelayMs(80)).isEqualTo(60_000);
    }

    private static RawArchiveGcManifest decodeDelete(Map<String, ?> fields) {
        return RawArchiveGcManifest.decodeManifest(GROUP, DELETE_FILES, fields);
    }
}
