/**
 * [INPUT]: 接收状态目录、归档配置和 SQLite 提交边界。
 * [OUTPUT]: 在单实例锁与群锁下提供分段路径、状态恢复、追加和有序读取。
 * [POS]: 分段存储入口；日志维护原文，索引只加速定位，路径层约束文件范围。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.BotProperties;
import dev.qqbot.RawArchiveProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RawSegmentStore implements AutoCloseable {
    public static final int HEADER_BYTES = RawSegmentFormat.HEADER_BYTES;
    public static final int MAX_PAYLOAD_BYTES = RawSegmentFormat.MAX_PAYLOAD_BYTES;
    private static final int GROUP_LOCK_COUNT = 64;
    private static final String LOCKED = "RAW_ARCHIVE_LOCKED";
    private final RawSegmentPaths paths;
    private final RawSegmentFormat format;
    private final RawSegmentLog log;
    private final RawSparseIndex indexes;
    private final ReentrantLock[] locks = new ReentrantLock[GROUP_LOCK_COUNT];
    private FileChannel lockChannel;
    private FileLock processLock;

    /** 以部署状态目录打开归档存储；此时未获取实例锁。 */
    @Autowired
    public RawSegmentStore(BotProperties bot, RawArchiveProperties properties, ObjectMapper json) {
        this(bot.stateDir(), properties, json);
    }

    /** 指定状态目录和索引限制；磁盘路径在使用时逐级校验。 */
    public RawSegmentStore(Path stateDir, RawArchiveProperties properties, ObjectMapper json) {
        this.paths = new RawSegmentPaths(stateDir);
        this.format = new RawSegmentFormat(json);
        this.log = new RawSegmentLog(paths, format, this::checkpoint);
        this.indexes = new RawSparseIndex(paths, format, properties);
        Arrays.setAll(locks, index -> new ReentrantLock());
    }

    /** 获取跨进程的独占锁；不能获取时抛出归档锁错误。 */
    public synchronized void acquire() {
        if (processLock != null) {
            return;
        }
        try {
            Path lockPath = paths.resolve(".raw-archive.lock");
            // SIDE EFFECT: 创建归档目录并保持锁文件通道，直到 close 释放独占锁。
            Files.createDirectories(paths.root());
            lockChannel =
                    FileChannel.open(
                            paths.safe(lockPath),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE);
            processLock = lockChannel.tryLock();
            if (processLock == null) {
                throw new RawArchiveException(LOCKED);
            }
        } catch (IOException | RuntimeException failure) {
            releaseFailedAcquire(failure);
            throw new RawArchiveException(LOCKED, failure);
        }
    }

    private void releaseFailedAcquire(Throwable failure) {
        try {
            close();
        } catch (RawArchiveException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /** 串行执行同一群的归档任务；任务结束或抛出异常时释放群锁。 */
    public <T> T locked(UUID groupId, Supplier<T> work) {
        ReentrantLock lock = locks[Math.floorMod(groupId.hashCode(), locks.length)];
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    /** 返回规范化的归档根目录。 */
    public Path root() {
        return paths.root();
    }

    /** 生成指定分段和扩展名的绝对路径；拒绝越界路径。 */
    public Path path(RawSegment segment, String extension) {
        return paths.path(segment, extension);
    }

    /** 将归档路径转为以斜杠分隔的相对路径。 */
    public String relative(Path path) {
        return paths.relative(path);
    }

    /** 解析归档相对路径；拒绝越界路径或链接。 */
    public Path resolve(String relative) {
        return paths.resolve(relative);
    }

    /** 校验路径位于归档根内，且没有经过链接。 */
    public Path safe(Path path) {
        return paths.safe(path);
    }

    /** 编码 V1 载荷；超过载荷上限时抛出归档错误。 */
    public byte[] payload(RawRecord record) {
        return format.encodePayload(record);
    }

    int frameBytes(RawRecord record) {
        return format.frameBytes(record);
    }

    /** 计算空分段的初始元数据，不创建文件。 */
    public RawSegment empty(RawPartition partition, long baseSeq, long now) {
        // UUID 只覆盖群、epoch 和起始序号；重放同一分段须得到同一身份。
        String identity = partition.groupId() + ":" + partition.epoch() + ":" + baseSeq;
        UUID id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        return new RawSegment(
                id,
                partition,
                baseSeq,
                baseSeq,
                0,
                HEADER_BYTES,
                0,
                now,
                Long.MAX_VALUE,
                Long.MIN_VALUE,
                "ACTIVE");
    }

    /** 追加按序记录并强制落盘；调用方在成功后发布新的提交边界。 */
    public synchronized RawSegment append(RawSegment segment, List<RawRecord> records) {
        return log.append(segment, records);
    }

    protected void checkpoint(String phase) {}

    /** 校验已提交帧，截断未提交尾部；已提交内容损坏时抛出异常。 */
    public synchronized void recover(RawSegment segment) {
        log.recover(segment);
    }

    /** 不依赖索引，校验全部已提交帧。 */
    public synchronized void verify(RawSegment segment) {
        log.streamVerified(segment, ignored -> {});
    }

    /** 校验文件头及已提交字节边界，不扫描帧。 */
    public synchronized void check(RawSegment segment) {
        log.check(segment);
    }

    /** 返回指定序号窗口中最多 limit 条记录；下界排除、上界包含。 */
    public List<RawRecord> read(RawSegment segment, long afterSeq, long throughSeq, int limit) {
        var page = new Page(limit);
        visit(segment, afterSeq, throughSeq, page::add);
        return List.copyOf(page.records);
    }

    public interface RecordVisitor {
        /** 接收一条原文；返回 false 时停止读取。 */
        boolean accept(RawRecord record);
    }

    /** 从可复核的索引起点读取窗口；访客可提前停止。 */
    public synchronized void visit(
            RawSegment segment, long afterSeq, long throughSeq, RecordVisitor visitor) {
        long position = indexes.startPosition(segment, afterSeq);
        log.visit(
                segment, new RawSegmentLog.Window(position, afterSeq, throughSeq), visitor::accept);
    }

    /** 逐条读取已提交窗口；需要核对完整段时使用 streamVerified。 */
    public void stream(RawSegment segment, Consumer<RawRecord> visitor) {
        visit(segment, 0, Long.MAX_VALUE, new StreamingVisitor(visitor));
    }

    /** 逐条读取并复核完整原文日志；不使用索引。 */
    public synchronized void streamVerified(RawSegment segment, Consumer<RawRecord> visitor) {
        log.streamVerified(segment, visitor);
    }

    /** 删除归档根内的文件或树；不跟随链接。 */
    public synchronized void delete(String relative) {
        paths.delete(relative);
    }

    /** 只删除与预期内容指纹相同的文件；文件已不存在时不写磁盘。 */
    public synchronized void deleteExpected(String relative, String expectedHash) {
        paths.deleteExpected(relative, expectedHash);
    }

    /** 关闭锁文件通道并释放实例锁；允许重复关闭。 */
    @PreDestroy
    @Override
    public synchronized void close() {
        try {
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException failure) {
            throw new RawArchiveException("RAW_ARCHIVE_LOCK_RELEASE_FAILED", failure);
        } finally {
            processLock = null;
            lockChannel = null;
        }
    }

    private static final class Page {
        private final List<RawRecord> records = new ArrayList<>();
        private final int limit;

        Page(int limit) {
            this.limit = limit;
        }

        boolean add(RawRecord record) {
            records.add(record);
            return records.size() < limit;
        }
    }

    private record StreamingVisitor(Consumer<RawRecord> visitor) implements RecordVisitor {
        @Override
        public boolean accept(RawRecord record) {
            visitor.accept(record);
            return true;
        }
    }
}
