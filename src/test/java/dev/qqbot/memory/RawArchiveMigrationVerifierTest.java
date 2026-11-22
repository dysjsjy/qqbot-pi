/**
 * [INPUT]: 使用合成旧版文档和以源序号排列的 Markdown 块。
 * [OUTPUT]: 验证兼容旧乱序，同时拒绝缺失、重复和额外内容。
 * [POS]: RAW 迁移的纯内容校验测试，不读实际归档。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class RawArchiveMigrationVerifierTest {
    private static final String FIRST = "\n## 第一条\n\n原文甲\n";
    private static final String SECOND = "\n## 第二条\n\n原文乙\n";
    private static final List<String> SOURCES = List.of(FIRST, SECOND);

    @Test
    void validateLegacyContent_distinguishesReorderFromSourceOrder() {
        assertFalse(
                RawArchiveMigrationVerifier.validateLegacyContent(
                        RawRecord.MARKDOWN_TITLE + FIRST + SECOND, SOURCES));
        assertTrue(
                RawArchiveMigrationVerifier.validateLegacyContent(
                        RawRecord.MARKDOWN_TITLE + SECOND + FIRST, SOURCES));
    }

    @Test
    void validateLegacyContent_rejectsMissingRepeatedAndTrailingBlocks() {
        List<String> mismatches = List.of(FIRST, FIRST + FIRST, FIRST + SECOND + "其他内容");
        for (String body : mismatches) {
            var error =
                    assertThrows(
                            RawArchiveException.class,
                            () ->
                                    RawArchiveMigrationVerifier.validateLegacyContent(
                                            RawRecord.MARKDOWN_TITLE + body, SOURCES));
            assertEquals("RAW_MIGRATION_CONTENT_MISMATCH", error.getMessage());
        }
    }
}
