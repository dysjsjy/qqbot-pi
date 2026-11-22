/**
 * [INPUT]: 使用临时分段、独立的字节格式断言与磁盘故障注入。
 * [OUTPUT]: 验证 V1 兼容、索引重建、未提交尾部恢复与路径约束。
 * [POS]: 原文存储的文件系统回归；不依赖 SQLite。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.memory.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class S02RawSegmentTest {
    @TempDir Path directory;

    RawSegmentStore store() {
        return new RawSegmentStore(directory, RawArchiveProperties.defaults(), new ObjectMapper());
    }

    RawRecord record(long seq) {
        return new RawRecord(
                seq,
                new UUID(0, seq),
                "key-" + seq,
                "sender",
                "名字",
                "中文😀\r\n\"quoted\"\n",
                1000,
                1001,
                null,
                "TEXT");
    }

    @Test
    void formatMatchesIndependentHeaderAndCrc() throws Exception {
        try (var store = store()) {
            var empty = store.empty(new RawPartition(new UUID(1, 2), 3), 7, 1000);
            var segment = store.append(empty, List.of(record(7)));
            byte[] bytes = Files.readAllBytes(store.path(segment, "log"));
            ByteBuffer header = ByteBuffer.wrap(bytes);
            assertEquals(0x51524157, header.getInt());
            assertEquals(1, header.getShort());
            assertEquals(64, header.getShort());
            assertEquals(1, header.getLong());
            assertEquals(2, header.getLong());
            assertEquals(3, header.getLong());
            assertEquals(empty.id().getMostSignificantBits(), header.getLong());
            assertEquals(empty.id().getLeastSignificantBits(), header.getLong());
            assertEquals(7, header.getLong());
            assertEquals(0, header.getInt());
            CRC32C crc = new CRC32C();
            crc.update(bytes, 0, 60);
            assertEquals((int) crc.getValue(), header.getInt());
            assertEquals(7, header.getLong());
            int length = header.getInt(), checksum = header.getInt();
            crc.reset();
            crc.update(bytes, 64, 12);
            crc.update(bytes, 80, length);
            assertEquals((int) crc.getValue(), checksum);
            assertEquals(
                    expectedPayload().strip(),
                    new String(bytes, 80, length, StandardCharsets.UTF_8));
            assertEquals(List.of(record(7)), store.read(segment, 0, 100, 100));
        }
    }

    private static String expectedPayload() {
        return """
                {"messageId":"00000000-0000-0000-0000-000000000007","messageKey":"key-7",\
                "senderId":"sender","senderName":"名字","body":"中文\\uD83D\\uDE00\\r\\n\\"quoted\\"\\n",\
                "occurredAtMs":1000,"receivedAtMs":1001,"replyToMessageKey":null,"kind":"TEXT"}
                """;
    }

    @Test
    void manyBatchesReuseSegmentAndRebuildIndex() throws Exception {
        try (var store = store()) {
            var segment = store.empty(new RawPartition(UUID.randomUUID(), 0), 1, 1000);
            for (int seq = 1; seq <= 100; seq++) {
                segment = store.append(segment, List.of(record(seq * 2 - 1)));
            }
            assertEquals(100, segment.recordCount());
            assertEquals(
                    List.of(record(151), record(153), record(155)),
                    store.read(segment, 150, 155, 100));
            Files.write(store.path(segment, "index"), new byte[] {1, 2, 3});
            assertEquals(
                    List.of(record(151), record(153), record(155)),
                    store.read(segment, 150, 155, 100));
            try (var files = Files.list(store.path(segment, "log").getParent())) {
                assertEquals(2, files.count());
            }
        }
    }

    @Test
    void recoveryDiscardsOnlyUncommittedTail() throws Exception {
        RawSegment committed;
        try (var store = store()) {
            committed =
                    store.append(
                            store.empty(new RawPartition(UUID.randomUUID(), 0), 1, 1000),
                            List.of(record(1)));
            store.append(committed, List.of(record(2)));
            Files.write(
                    store.path(committed, "log"), new byte[] {1, 2, 3}, StandardOpenOption.APPEND);
        }
        try (var restarted = store()) {
            restarted.recover(committed);
            assertEquals(committed.committedBytes(), Files.size(restarted.path(committed, "log")));
            var next = restarted.append(committed, List.of(record(2)));
            assertEquals(2, next.recordCount());
            restarted.verify(next);
        }
    }

    @Test
    void committedCorruptionAndTruncationAreRejected() throws Exception {
        try (var store = store()) {
            var segment =
                    store.append(
                            store.empty(new RawPartition(UUID.randomUUID(), 0), 1, 0),
                            List.of(record(1)));
            try (var channel =
                    FileChannel.open(store.path(segment, "log"), StandardOpenOption.WRITE)) {
                channel.position(85);
                channel.write(ByteBuffer.wrap(new byte[] {0}));
            }
            assertEquals(
                    "RAW_ARCHIVE_CORRUPT",
                    assertThrows(RawArchiveException.class, () -> store.verify(segment))
                            .getMessage());
            try (var channel =
                    FileChannel.open(store.path(segment, "log"), StandardOpenOption.WRITE)) {
                channel.truncate(63);
            }
            assertThrows(RawArchiveException.class, () -> store.verify(segment));
        }
    }

    @Test
    void failedForceDoesNotMakeTailCommitted() {
        try (var store =
                new RawSegmentStore(
                        directory, RawArchiveProperties.defaults(), new ObjectMapper()) {
                    @Override
                    protected void checkpoint(String phase) {
                        if (phase.equals("beforeForce")) {
                            throw new RawArchiveException("INJECTED");
                        }
                    }
                }) {
            var empty = store.empty(new RawPartition(UUID.randomUUID(), 0), 1, 0);
            assertThrows(RawArchiveException.class, () -> store.append(empty, List.of(record(1))));
            store.recover(empty);
            assertTrue(store.read(empty, 0, 100, 100).isEmpty());
        }
    }

    @Test
    void rootEscapeAndSecondOwnerRejected() {
        try (var first = store();
                var second = store()) {
            assertThrows(RawArchiveException.class, () -> first.resolve("../outside"));
            first.acquire();
            assertThrows(RawArchiveException.class, second::acquire);
        }
    }

    @Test
    void oversizePayloadRejected() {
        try (var store = store()) {
            var sample = record(1);
            var oversized =
                    new RawRecord(
                            1,
                            sample.messageId(),
                            null,
                            null,
                            null,
                            "x".repeat(1048577),
                            0,
                            0,
                            null,
                            "TEXT");
            assertEquals(
                    "RAW_RECORD_TOO_LARGE",
                    assertThrows(RawArchiveException.class, () -> store.payload(oversized))
                            .getMessage());
        }
    }

    @Test
    void append_rejectsDescendingSequenceBeforeCreatingLog() {
        try (var store = store()) {
            var segment = store.empty(new RawPartition(UUID.randomUUID(), 0), 1, 0);

            var failure =
                    assertThrows(
                            RawArchiveException.class,
                            () -> store.append(segment, List.of(record(2), record(1))));

            assertEquals("RAW_SEQUENCE_INVALID", failure.getMessage());
            assertFalse(Files.exists(store.path(segment, "log")));
        }
    }
}
