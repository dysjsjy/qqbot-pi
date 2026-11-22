/**
 * [INPUT]: 提供固定群范围、来源和索引分块号。
 * [OUTPUT]: 验证历史兼容的分块身份及代际隔离。
 * [POS]: 词法索引身份计算的纯规则测试。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemorySearchWriterTest {
    @Test
    void chunkId_preservesLegacyIdentityAndChangesAcrossEpochs() {
        UUID group = UUID.fromString("6111dc98-3594-440f-87a9-9f9c2274eee7");
        String source = "summary:015e4a20-1ed4-4fbb-848c-fe8782f00216";
        var firstEpoch = new MemorySearchWriter.Scope(group, 1);
        var secondEpoch = new MemorySearchWriter.Scope(group, 2);
        String legacyIdentity = group + ":1:SUMMARY:" + source + ":0";
        UUID expected = UUID.nameUUIDFromBytes(legacyIdentity.getBytes(StandardCharsets.UTF_8));

        UUID first = MemorySearchWriter.chunkId(firstEpoch, SourceKind.SUMMARY, source, 0);

        assertEquals(expected, first);
        assertNotEquals(
                first, MemorySearchWriter.chunkId(secondEpoch, SourceKind.SUMMARY, source, 0));
        assertNotEquals(
                first, MemorySearchWriter.chunkId(firstEpoch, SourceKind.SUMMARY, source, 1));
    }
}
