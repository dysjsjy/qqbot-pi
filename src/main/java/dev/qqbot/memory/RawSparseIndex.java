/**
 * [INPUT]: 接收已提交分段、索引间隔和缓存上限。
 * [OUTPUT]: 返回分页起点；索引无效时以原文帧重建并替换索引。
 * [POS]: 可重建的加速层。调用方持有存储锁，原文日志才是权威来源。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static dev.qqbot.memory.RawSegmentFormat.HEADER_BYTES;
import static dev.qqbot.memory.RawSegmentFormat.INDEX_MAGIC;
import static dev.qqbot.memory.RawSegmentFormat.LOG_MAGIC;

import dev.qqbot.RawArchiveProperties;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class RawSparseIndex {
    private static final int ENTRY_BYTES = 24;
    private final RawSegmentPaths paths;
    private final RawSegmentFormat format;
    private final int intervalBytes;
    private final int capacity;
    private final Map<Generation, CachedIndex> indexes = new LinkedHashMap<>();

    RawSparseIndex(
            RawSegmentPaths paths, RawSegmentFormat format, RawArchiveProperties properties) {
        this.paths = paths;
        this.format = format;
        this.intervalBytes = properties.indexIntervalBytes();
        this.capacity = properties.maxOpenSegments();
    }

    long startPosition(RawSegment segment, long afterSequence) {
        long position = HEADER_BYTES;
        for (Entry entry : index(segment)) {
            if (entry.sequence() > afterSequence) {
                break;
            }
            position = entry.position();
        }
        return position;
    }

    private List<Entry> index(RawSegment segment) {
        Path file = paths.path(segment, "index");
        var key = new Generation(segment.id(), segment.generation());
        try {
            CachedIndex cached = indexes.get(key);
            if (cached != null && cached.matches(file, segment.committedBytes())) {
                return cached.entries();
            }
            List<Entry> entries = loadIndex(file, segment);
            remember(key, new CachedIndex(segment.committedBytes(), FileStamp.read(file), entries));
            return entries;
        } catch (IOException failure) {
            throw new RawArchiveException("RAW_INDEX_REBUILD_FAILED", failure);
        }
    }

    private List<Entry> loadIndex(Path file, RawSegment segment) throws IOException {
        Optional<List<Entry>> existing = findValidIndex(file, segment);
        List<Entry> entries = extendIndex(segment, existing.orElseGet(List::of));
        if (existing.isEmpty() || existing.orElseThrow().size() != entries.size()) {
            replaceIndex(file, segment, entries);
        }
        return entries;
    }

    private Optional<List<Entry>> findValidIndex(Path file, RawSegment segment) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(readIndex(file, segment));
        } catch (IOException | RuntimeException invalid) {
            // 索引可重建。若原文损坏，随后重扫原文时仍会报错。
            return Optional.empty();
        }
    }

    private List<Entry> readIndex(Path file, RawSegment segment) throws IOException {
        var entries = new ArrayList<Entry>();
        try (var channel = FileChannel.open(file, StandardOpenOption.READ);
                var log = FileChannel.open(paths.path(segment, "log"), StandardOpenOption.READ)) {
            RawSegmentFormat.checkHeader(channel, segment, INDEX_MAGIC);
            requireValidSize(channel.size(), segment);
            Entry previous = null;
            while (channel.position() < channel.size()) {
                Entry entry = readEntry(channel);
                requireValidEntry(segment, entry, previous);
                requireMatchingFrame(log, segment, entry);
                entries.add(entry);
                previous = entry;
            }
        }
        return entries;
    }

    private void requireValidSize(long bytes, RawSegment segment) throws IOException {
        long entryBytes = bytes - HEADER_BYTES;
        long maxEntries =
                Math.min(
                        segment.recordCount(),
                        (segment.committedBytes() - HEADER_BYTES) / intervalBytes + 1);
        if (entryBytes % ENTRY_BYTES != 0 || entryBytes / ENTRY_BYTES > maxEntries) {
            throw new IOException("Invalid RAW index size: " + bytes);
        }
    }

    private static Entry readEntry(FileChannel channel) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(ENTRY_BYTES);
        RawSegmentFormat.read(channel, bytes);
        bytes.flip();
        return new Entry(bytes.getLong(), bytes.getLong(), bytes.getLong());
    }

    private static void requireValidEntry(RawSegment segment, Entry entry, Entry previous)
            throws IOException {
        boolean isInBounds =
                entry.position() >= HEADER_BYTES
                        && entry.position() < segment.committedBytes()
                        && entry.ordinal() >= 0
                        && entry.ordinal() < segment.recordCount();
        boolean isOrdered =
                previous == null
                        ? entry.position() == HEADER_BYTES && entry.ordinal() == 0
                        : entry.follows(previous);
        if (!isInBounds || !isOrdered) {
            throw new IOException("Invalid RAW index ordering: " + entry);
        }
    }

    private void requireMatchingFrame(FileChannel channel, RawSegment segment, Entry entry)
            throws IOException {
        channel.position(entry.position());
        if (format.readFrame(channel, segment.committedBytes()).seq() != entry.sequence()) {
            throw new IOException("Invalid RAW index position: " + entry);
        }
    }

    private List<Entry> extendIndex(RawSegment segment, List<Entry> original) throws IOException {
        var entries = new ArrayList<>(original);
        Entry tail = entries.isEmpty() ? null : entries.get(entries.size() - 1);
        long ordinal = tail == null ? 0 : tail.ordinal();
        try (var log = FileChannel.open(paths.path(segment, "log"), StandardOpenOption.READ)) {
            RawSegmentFormat.checkHeader(log, segment, LOG_MAGIC);
            log.position(tail == null ? HEADER_BYTES : tail.position());
            while (log.position() < segment.committedBytes()) {
                long position = log.position();
                RawRecord record = format.readFrame(log, segment.committedBytes());
                if (tail == null || position - tail.position() >= intervalBytes) {
                    tail = new Entry(record.seq(), position, ordinal);
                    entries.add(tail);
                }
                ordinal++;
            }
        }
        return List.copyOf(entries);
    }

    private void replaceIndex(Path file, RawSegment segment, List<Entry> entries)
            throws IOException {
        Path temporary = paths.safe(file.resolveSibling(file.getFileName() + ".tmp"));
        // SIDE EFFECT: 先刷新完整临时索引，再替换磁盘上的派生索引。
        writeIndex(temporary, segment, entries);
        try {
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeIndex(Path file, RawSegment segment, List<Entry> entries)
            throws IOException {
        try (var channel =
                FileChannel.open(
                        file,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            RawSegmentFormat.write(channel, RawSegmentFormat.fileHeader(segment, INDEX_MAGIC));
            for (Entry entry : entries) {
                RawSegmentFormat.write(channel, entry.encode());
            }
            channel.force(true);
        }
    }

    private void remember(Generation key, CachedIndex entry) {
        indexes.put(key, entry);
        while (indexes.size() > capacity) {
            indexes.remove(indexes.keySet().iterator().next());
        }
    }

    private record Entry(long sequence, long position, long ordinal) {
        boolean follows(Entry previous) {
            return sequence > previous.sequence
                    && position > previous.position
                    && ordinal > previous.ordinal;
        }

        ByteBuffer encode() {
            ByteBuffer bytes = ByteBuffer.allocate(ENTRY_BYTES);
            bytes.putLong(sequence);
            bytes.putLong(position);
            bytes.putLong(ordinal);
            return bytes.flip();
        }
    }

    private record Generation(UUID segment, long generation) {}

    private record FileStamp(long size, FileTime modified) {
        static FileStamp read(Path file) throws IOException {
            return new FileStamp(Files.size(file), Files.getLastModifiedTime(file));
        }
    }

    private record CachedIndex(long boundary, FileStamp stamp, List<Entry> entries) {
        boolean matches(Path file, long committedBytes) throws IOException {
            return boundary == committedBytes
                    && Files.exists(file)
                    && stamp.equals(FileStamp.read(file));
        }
    }
}
