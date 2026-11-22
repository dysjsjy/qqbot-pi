/**
 * [INPUT]: 使用带序号缺口、未来文本及不同帧尺寸的候选。
 * [OUTPUT]: 验证批次选择及已扫描水位。
 * [POS]: 在线 RAW 扫描的纯计算回归；不读写 SQLite 或分段文件。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.qqbot.memory.RawArchiveService.Batch;
import dev.qqbot.memory.RawArchiveService.Candidate;
import dev.qqbot.memory.RawArchiveService.Window;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RawArchiveScanTest {
    private static final Instant NOW = Instant.parse("2026-11-22T03:00:00Z");
    private static final Window WINDOW =
            new Window(new RawPartition(new UUID(0, 1), 0), 4, NOW, NOW);
    private static final int FRAME_BYTES = 100;

    @Test
    void planBatch_recognizesSkippedRowsButStopsBeforeFutureText() {
        RawRecord first = record(8);
        List<Candidate> candidates =
                List.of(
                        new Candidate(6, false, null),
                        new Candidate(8, false, first),
                        new Candidate(10, true, null),
                        new Candidate(11, false, record(11)));

        Batch batch =
                RawArchiveService.planBatch(
                        WINDOW, candidates, 4 * FRAME_BYTES, record -> FRAME_BYTES);

        assertEquals(8, batch.scanned());
        assertEquals(List.of(first), batch.records());
    }

    @Test
    void planBatch_keepsOversizeFirstRecordButDoesNotSkipNextUnwrittenRecord() {
        RawRecord first = record(8);
        List<Candidate> candidates =
                List.of(new Candidate(8, false, first), new Candidate(12, false, record(12)));

        Batch batch =
                RawArchiveService.planBatch(
                        WINDOW, candidates, FRAME_BYTES - 1, record -> FRAME_BYTES);

        assertEquals(8, batch.scanned());
        assertEquals(List.of(first), batch.records());
    }

    @Test
    void planBatch_doesNotAdvanceWhenFirstTextIsInFuture() {
        Batch batch =
                RawArchiveService.planBatch(
                        WINDOW,
                        List.of(new Candidate(7, true, null)),
                        FRAME_BYTES,
                        record -> FRAME_BYTES);

        assertFalse(batch.advancesWatermark());
        assertEquals(List.of(), batch.records());
    }

    private static RawRecord record(long sequence) {
        long timestamp = NOW.toEpochMilli();
        return new RawRecord(
                sequence,
                new UUID(1, sequence),
                "key",
                "sender",
                "成员",
                "正文",
                timestamp,
                timestamp,
                null,
                RawRecord.TEXT);
    }
}
