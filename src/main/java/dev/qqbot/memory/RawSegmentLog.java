/**
 * [INPUT]: 接收已校验路径、V1 帧格式和 SQLite 提交边界。
 * [OUTPUT]: 写入并刷新帧；恢复时只截断未提交尾部；验证读取不依赖索引。
 * [POS]: 原文日志的 I/O 边界；调用方持有存储锁，不在此更新 SQLite。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static dev.qqbot.memory.RawSegmentFormat.CORRUPT;
import static dev.qqbot.memory.RawSegmentFormat.HEADER_BYTES;
import static dev.qqbot.memory.RawSegmentFormat.LOG_MAGIC;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class RawSegmentLog {
    private static final int RECOVERED_CACHE_CAPACITY = 64;
    private final RawSegmentPaths paths;
    private final RawSegmentFormat format;
    private final Consumer<String> checkpoint;
    private final Map<Generation, Long> recoveredBoundaries = new LinkedHashMap<>();

    RawSegmentLog(RawSegmentPaths paths, RawSegmentFormat format, Consumer<String> checkpoint) {
        this.paths = paths;
        this.format = format;
        this.checkpoint = checkpoint;
    }

    RawSegment append(RawSegment segment, List<RawRecord> records) {
        if (records.isEmpty()) {
            return segment;
        }
        requireAppendOrder(segment, records);
        Path file = logPath(segment);
        try {
            // SIDE EFFECT: 写入文件不会推进 SQLite 的提交边界。
            ensureLogExists(file, segment);
            recover(segment);
            RawSegment appended = appendFrames(file, segment, records);
            remember(appended);
            return appended;
        } catch (IOException failure) {
            throw new RawArchiveException("RAW_ARCHIVE_IO", failure);
        }
    }

    private static void requireAppendOrder(RawSegment segment, List<RawRecord> records) {
        long previous = segment.recordCount() == 0 ? 0 : segment.lastSeq();
        for (RawRecord record : records) {
            if (record.seq() <= previous || record.seq() < segment.baseSeq()) {
                throw new RawArchiveException("RAW_SEQUENCE_INVALID");
            }
            previous = record.seq();
        }
    }

    private Path logPath(RawSegment segment) {
        return paths.path(segment, "log");
    }

    private void ensureLogExists(Path file, RawSegment segment) throws IOException {
        Files.createDirectories(paths.safe(file.getParent()));
        paths.safe(file);
        if (Files.exists(file)) {
            return;
        }
        if (segment.recordCount() > 0) {
            throw new RawArchiveException(CORRUPT);
        }
        try (var channel =
                FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            RawSegmentFormat.write(channel, RawSegmentFormat.fileHeader(segment, LOG_MAGIC));
            channel.force(true);
            checkpoint.accept("headerForced");
        }
    }

    private RawSegment appendFrames(Path file, RawSegment segment, List<RawRecord> records)
            throws IOException {
        long minimumTime = segment.minOccurredMs();
        long maximumTime = segment.maxOccurredMs();
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.position(segment.committedBytes());
            for (RawRecord record : records) {
                format.writeFrame(channel, record, checkpoint);
                minimumTime = Math.min(minimumTime, record.occurredAtMs());
                maximumTime = Math.max(maximumTime, record.occurredAtMs());
            }
            checkpoint.accept("beforeForce");
            channel.force(true);
            checkpoint.accept("afterForce");
            return appendedSegment(
                    segment, records, channel.position(), new TimeBounds(minimumTime, maximumTime));
        }
    }

    private static RawSegment appendedSegment(
            RawSegment segment, List<RawRecord> records, long boundary, TimeBounds times) {
        return new RawSegment(
                segment.id(),
                segment.partition(),
                segment.baseSeq(),
                records.get(records.size() - 1).seq(),
                segment.generation(),
                boundary,
                segment.recordCount() + records.size(),
                segment.openedMs(),
                times.minimum(),
                times.maximum(),
                segment.state());
    }

    void recover(RawSegment segment) {
        try (var channel =
                FileChannel.open(
                        logPath(segment), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            RawSegmentFormat.checkHeader(channel, segment, LOG_MAGIC);
            long size = requireCommittedSize(channel, segment);
            if (!hasVerifiedBoundary(segment)) {
                readVerified(channel, segment, ignored -> {});
            }
            if (size > segment.committedBytes()) {
                // SIDE EFFECT: 只截断 SQLite 水位之后的未提交尾部。
                channel.truncate(segment.committedBytes());
                channel.force(true);
            }
            remember(segment);
        } catch (IOException failure) {
            throw new RawArchiveException(CORRUPT, failure);
        }
    }

    void check(RawSegment segment) {
        try (var channel = FileChannel.open(logPath(segment), StandardOpenOption.READ)) {
            RawSegmentFormat.checkHeader(channel, segment, LOG_MAGIC);
            requireCommittedSize(channel, segment);
        } catch (IOException failure) {
            throw new RawArchiveException(CORRUPT, failure);
        }
    }

    private static long requireCommittedSize(FileChannel channel, RawSegment segment)
            throws IOException {
        long size = channel.size();
        if (size < segment.committedBytes()) {
            throw new RawArchiveException(CORRUPT);
        }
        return size;
    }

    void streamVerified(RawSegment segment, Consumer<RawRecord> visitor) {
        try (var channel = FileChannel.open(logPath(segment), StandardOpenOption.READ)) {
            RawSegmentFormat.checkHeader(channel, segment, LOG_MAGIC);
            readVerified(channel, segment, visitor);
        } catch (IOException failure) {
            throw new RawArchiveException(CORRUPT, failure);
        }
    }

    private void readVerified(FileChannel channel, RawSegment segment, Consumer<RawRecord> visitor)
            throws IOException {
        channel.position(HEADER_BYTES);
        long count = 0;
        long previous = 0;
        while (channel.position() < segment.committedBytes()) {
            RawRecord record = format.readFrame(channel, segment.committedBytes());
            if (record.seq() <= previous || record.seq() < segment.baseSeq()) {
                throw new RawArchiveException(CORRUPT);
            }
            visitor.accept(record);
            previous = record.seq();
            count++;
        }
        if (count != segment.recordCount() || (count > 0 && previous != segment.lastSeq())) {
            throw new RawArchiveException(CORRUPT);
        }
    }

    void visit(RawSegment segment, Window window, Predicate<RawRecord> visitor) {
        try (var channel = FileChannel.open(logPath(segment), StandardOpenOption.READ)) {
            RawSegmentFormat.checkHeader(channel, segment, LOG_MAGIC);
            channel.position(window.position());
            visitFrames(channel, segment.committedBytes(), window, visitor);
        } catch (IOException failure) {
            throw new RawArchiveException(CORRUPT, failure);
        }
    }

    private void visitFrames(
            FileChannel channel, long boundary, Window window, Predicate<RawRecord> visitor)
            throws IOException {
        long previous = 0;
        while (channel.position() < boundary) {
            RawRecord record = format.readFrame(channel, boundary);
            if (record.seq() <= previous) {
                throw new RawArchiveException(CORRUPT);
            }
            previous = record.seq();
            if (record.seq() > window.throughSequence()) {
                break;
            }
            if (record.seq() > window.afterSequence() && !visitor.test(record)) {
                break;
            }
        }
    }

    private boolean hasVerifiedBoundary(RawSegment segment) {
        var key = new Generation(segment.id(), segment.generation());
        return Objects.equals(recoveredBoundaries.get(key), segment.committedBytes());
    }

    private void remember(RawSegment segment) {
        recoveredBoundaries.put(
                new Generation(segment.id(), segment.generation()), segment.committedBytes());
        while (recoveredBoundaries.size() > RECOVERED_CACHE_CAPACITY) {
            recoveredBoundaries.remove(recoveredBoundaries.keySet().iterator().next());
        }
    }

    record Window(long position, long afterSequence, long throughSequence) {}

    private record TimeBounds(long minimum, long maximum) {}

    private record Generation(UUID id, long generation) {}
}
