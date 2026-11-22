/**
 * [INPUT]: 接收 RAW 文档作用域、游标和分页上限。
 * [OUTPUT]: 返回稳定窗口下的 Markdown、来源与续页游标。
 * [POS]: 持群归档锁读取；仅在文件损坏时写回故障状态。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.RetentionProperties;
import dev.qqbot.memory.RawArchiveCursor.DocumentScope;
import dev.qqbot.memory.RawArchiveCursor.Position;
import dev.qqbot.persistence.Db;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.web.server.ResponseStatusException;

final class RawArchivePageReader {
    private static final int MAX_PAGE_MESSAGES = 200;
    private static final int MAX_MARKDOWN_BYTES = 2 * 1024 * 1024;
    private static final String CORRUPT = "RAW_ARCHIVE_CORRUPT";
    private static final String PAGE_EXPIRED = "RAW_PAGE_EXPIRED";

    private final Db db;
    private final RawSegmentStore store;
    private final Clock clock;
    private final RetentionProperties retention;
    private final RawArchiveCursor cursor;

    RawArchivePageReader(Db db, RawSegmentStore store, Clock clock, RetentionProperties retention) {
        this.db = db;
        this.store = store;
        this.clock = clock;
        this.retention = retention;
        this.cursor = new RawArchiveCursor(db);
    }

    Map<String, Object> readDocument(UUID group, UUID document, String token, int limit) {
        Objects.requireNonNull(group, "group must not be null");
        Objects.requireNonNull(document, "document must not be null");
        if (limit < 1 || limit > MAX_PAGE_MESSAGES) {
            throw RawArchiveCursor.invalidCursor();
        }
        return store.locked(group, () -> readLocked(group, document, token, limit));
    }

    private Map<String, Object> readLocked(UUID group, UUID document, String token, int limit) {
        DocumentScope scope = requireScope(group, document);
        Map<String, Object> fields =
                findDocumentFields(scope).orElseThrow(RawArchivePageReader::notFound);
        Snapshot snapshot = pinSnapshot(scope, token);
        List<Source> page = loadSources(scope, snapshot.window(), snapshot.after(), limit + 1);
        List<RawSegment> segments = loadSegments(scope);
        PageContent content = readPageRecords(page, segments, limit, snapshot.after());
        requireUnchangedSnapshot(scope, snapshot);
        var result =
                new PageResult(
                        content, page.size() > content.ids.size(), hasActiveSegment(segments));
        return serializePage(scope, fields, snapshot, result);
    }

    private DocumentScope requireScope(UUID group, UUID document) {
        long epoch = findEpoch(group).orElseThrow(RawArchivePageReader::notFound);
        return new DocumentScope(new RawPartition(group, epoch), document);
    }

    private Snapshot pinSnapshot(DocumentScope scope, String token) {
        Optional<Position> position = cursor.decodeCursor(scope, token);
        Instant now = clock.instant();
        Instant cutoff = retention.archiveCutoff(now);
        long ceiling = position.map(Position::through).orElse(Long.MAX_VALUE);
        Totals totals = totals(scope, new Window(cutoff, now, ceiling));
        if (totals.count() == 0) {
            throw position.isPresent()
                    ? pageExpired()
                    : new ResponseStatusException(HttpStatus.NOT_FOUND, "RAW_DOCUMENT_EXPIRED");
        }
        long through = position.map(Position::through).orElse(totals.highestSeq());
        Window window = new Window(cutoff, now, through);
        String members = fingerprint(scope, window);
        if (!position.map(Position::members).orElse(members).equals(members)) {
            throw pageExpired();
        }
        return new Snapshot(
                window, position.map(Position::after).orElse(0L), totals.count(), members);
    }

    private void requireUnchangedSnapshot(DocumentScope scope, Snapshot snapshot) {
        long current =
                findEpoch(scope.partition().groupId()).orElseThrow(RawArchivePageReader::notFound);
        if (current != scope.partition().epoch() || !hasDocument(scope)) {
            throw notFound();
        }
        Window currentWindow =
                new Window(
                        retention.archiveCutoff(clock.instant()),
                        clock.instant(),
                        snapshot.window().through());
        if (!snapshot.members().equals(fingerprint(scope, currentWindow))) {
            throw pageExpired();
        }
    }

    private PageContent readPageRecords(
            List<Source> page, List<RawSegment> segments, int limit, long after) {
        var content = new PageContent(after);
        try {
            for (Source source : page) {
                if (content.ids.size() >= limit) {
                    break;
                }
                RawRecord record = readRecord(requireSegment(segments, source.sequence()), source);
                if (!content.appendIfFits(record)) {
                    break;
                }
            }
        } catch (RawArchiveException failure) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, failure.getMessage(), failure);
        }
        return content;
    }

    private static RawSegment requireSegment(List<RawSegment> segments, long sequence) {
        RawSegment segment =
                segments.stream()
                        .filter(value -> covers(value, sequence))
                        .findFirst()
                        .orElseThrow(() -> new RawArchiveException(CORRUPT));
        if ("BROKEN".equals(segment.state())) {
            throw new RawArchiveException(CORRUPT);
        }
        return segment;
    }

    private static boolean covers(RawSegment segment, long sequence) {
        return segment.baseSeq() <= sequence && sequence <= segment.lastSeq();
    }

    private RawRecord readRecord(RawSegment segment, Source source) {
        List<RawRecord> found = readSegment(segment, source.sequence());
        if (found.size() != 1 || !found.get(0).messageId().equals(source.id())) {
            markBroken(segment);
            throw new RawArchiveException(CORRUPT);
        }
        return found.get(0);
    }

    private List<RawRecord> readSegment(RawSegment segment, long sequence) {
        try {
            return store.read(segment, sequence - 1, sequence, 1);
        } catch (RawArchiveException failure) {
            if (CORRUPT.equals(failure.getMessage())) {
                markBroken(segment);
            }
            throw failure;
        }
    }

    private void markBroken(RawSegment segment) {
        // SIDE EFFECT: 分段及扫描状态的故障标记一起提交，避免扫描继续追加。
        db.tx(() -> persistCorruption(segment));
    }

    private Void persistCorruption(RawSegment segment) {
        db.exec(
                "update raw_archive_segment set state='BROKEN' where id=? and generation=?",
                segment.id(),
                segment.generation());
        db.exec(
                """
                update raw_archive_state set status='FAILED',error_code=? where group_id=? and epoch=?
                """,
                CORRUPT,
                segment.partition().groupId(),
                segment.partition().epoch());
        return null;
    }

    private Map<String, Object> serializePage(
            DocumentScope scope,
            Map<String, Object> document,
            Snapshot snapshot,
            PageResult result) {
        long through = snapshot.window().through();
        PageContent content = result.content();
        var next = new Position(content.last, through, snapshot.members());
        String token = result.hasMore() ? cursor.encodeCursor(scope, next) : null;
        document.put("content", content.markdown.toString());
        document.put("sources", content.ids.stream().map(id -> Map.of("message_id", id)).toList());
        document.put("totalSources", snapshot.count());
        document.put("snapshotSeq", Long.toString(through));
        document.put("nextCursor", token);
        document.put("open", result.isOpen());
        return document;
    }

    private static boolean hasActiveSegment(List<RawSegment> segments) {
        return segments.stream().anyMatch(segment -> "ACTIVE".equals(segment.state()));
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static ResponseStatusException pageExpired() {
        return new ResponseStatusException(HttpStatus.CONFLICT, PAGE_EXPIRED);
    }

    private record Snapshot(Window window, long after, long count, String members) {}

    private record PageResult(PageContent content, boolean hasMore, boolean isOpen) {}

    private static final class PageContent {
        private final StringBuilder markdown = new StringBuilder(RawRecord.MARKDOWN_TITLE);
        private final List<UUID> ids = new ArrayList<>();
        private long last;
        private int bytes;

        PageContent(long after) {
            this.last = after;
        }

        boolean appendIfFits(RawRecord record) {
            String block = record.markdown();
            int size = block.getBytes(StandardCharsets.UTF_8).length;
            if (!ids.isEmpty() && bytes + size > MAX_MARKDOWN_BYTES) {
                return false;
            }
            markdown.append(block);
            bytes += size;
            ids.add(record.messageId());
            last = record.seq();
            return true;
        }
    }

    private static final String FILTER =
            """
             from memory_document_source s join group_message m on m.id=s.message_id
             where s.document_id=? and s.kind='RAW' and m.group_id=? and m.epoch=? and m.kind='TEXT'
             and m.occurred_at>=? and m.occurred_at<=? and m.event_seq<=?
            """;

    private Optional<Long> findEpoch(UUID group) {
        return db.find(
                "select epoch from group_config where id=?",
                (row, rowNumber) -> row.getLong(1),
                group);
    }

    private Optional<Map<String, Object>> findDocumentFields(DocumentScope scope) {
        return Optional.ofNullable(
                db.one(
                        """
                select * from memory_document where id=? and group_id=? and epoch=? and storage='SEGMENT_V1'
                """,
                        scope.documentId(),
                        scope.partition().groupId(),
                        scope.partition().epoch()));
    }

    private boolean hasDocument(DocumentScope scope) {
        return db.find(
                        "select 1 from memory_document where id=? and group_id=? and epoch=?",
                        (row, rowNumber) -> true,
                        scope.documentId(),
                        scope.partition().groupId(),
                        scope.partition().epoch())
                .isPresent();
    }

    private Totals totals(DocumentScope scope, Window window) {
        return db.find(
                        "select count(*),coalesce(max(m.event_seq),0)" + FILTER,
                        (row, rowNumber) -> new Totals(row.getLong(1), row.getLong(2)),
                        bindings(scope, window))
                .orElseThrow();
    }

    private List<Source> loadSources(DocumentScope scope, Window window, long after, int limit) {
        Object[] filterBindings = bindings(scope, window);
        Object[] arguments = Arrays.copyOf(filterBindings, filterBindings.length + 2);
        arguments[filterBindings.length] = after;
        arguments[filterBindings.length + 1] = limit;
        return db.query(
                "select m.id,m.event_seq"
                        + FILTER
                        + " and m.event_seq>? order by m.event_seq limit ?",
                RawArchivePageReader::readSource,
                arguments);
    }

    private List<RawSegment> loadSegments(DocumentScope scope) {
        return db.query(
                """
                select a.* from raw_archive_segment a join raw_archive_ref r on r.segment_id=a.id
                where r.document_id=? order by a.base_seq
                """,
                RawSegment::fromSqliteRow,
                scope.documentId());
    }

    private static Source readSource(ResultSet row, int rowNumber) throws SQLException {
        return new Source(UUID.fromString(row.getString(1)), row.getLong(2));
    }

    private String fingerprint(DocumentScope scope, Window window) {
        // 指纹仅覆盖窗口内按序排列的来源 ID 和序号；正文由分段校验，不参与游标成员校验。
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            RowCallbackHandler addMember = row -> hashMember(digest, row);
            db.jdbc.query(
                    "select m.id,m.event_seq" + FILTER + " order by m.event_seq",
                    addMember,
                    bindings(scope, window));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required for RAW page snapshots", failure);
        }
    }

    private static void hashMember(MessageDigest digest, ResultSet row) throws SQLException {
        String member = row.getString(1) + ":" + row.getLong(2) + "\n";
        digest.update(member.getBytes(StandardCharsets.UTF_8));
    }

    private static Object[] bindings(DocumentScope scope, Window window) {
        return new Object[] {
            scope.documentId().toString(),
            scope.partition().groupId().toString(),
            scope.partition().epoch(),
            window.cutoff().toEpochMilli(),
            window.now().toEpochMilli(),
            window.through()
        };
    }

    private record Totals(long count, long highestSeq) {}

    private record Window(Instant cutoff, Instant now, long through) {}

    private record Source(UUID id, long sequence) {}
}
