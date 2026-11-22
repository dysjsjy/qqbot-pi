/**
 * [INPUT]: 接收 SQLite 归档索引、旧版 Markdown 和分段文件。
 * [OUTPUT]: 返回只读检查报告；拒绝不一致的原文、计数或分段引用。
 * [POS]: RAW 迁移的校验边界；不截断、补写或发布文件。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.persistence.Db;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

final class RawArchiveMigrationVerifier {
    private static final int MAX_LEGACY_SOURCES = 100;
    private static final long MAX_LEGACY_BYTES = 128L * 1024 * 1024;
    private static final String CONTENT_MISMATCH = "RAW_MIGRATION_CONTENT_MISMATCH";
    private static final String DOCUMENT_TOO_LARGE = "RAW_MIGRATION_DOCUMENT_TOO_LARGE";
    private static final String DOCUMENT_COLUMNS =
            "select id,group_id,epoch,state,source_count from memory_document";
    private static final String SEGMENT_REFERENCE =
            """
            from memory_document_source s join memory_document d on d.id=s.document_id
            """;

    private final Db db;
    private final RawSegmentStore store;

    RawArchiveMigrationVerifier(Db db, RawSegmentStore store) {
        this.db = db;
        this.store = store;
    }

    Map<String, Object> inspect(UUID group) {
        return inspectLegacy(group, hasSegmentSchema()).serialize();
    }

    Map<String, Object> verify(UUID group) {
        boolean isUpgraded = hasSegmentSchema();
        Inspection legacy = inspectLegacy(group, isUpgraded);
        if (!isUpgraded) {
            return legacy.serialize();
        }
        if (!legacy.errors().isEmpty()) {
            throw new RawArchiveException(CONTENT_MISMATCH);
        }
        long records = verifySegments(group);
        long sources = requireReferenceCoverage(group, records);
        return Map.of(
                "ok",
                true,
                "records",
                records,
                "sources",
                sources,
                "verifiedLegacyDocuments",
                legacy.verified());
    }

    private boolean hasSegmentSchema() {
        return db.find(
                        "select 1 from sqlite_master where type='table' and name='raw_archive_segment'",
                        (row, rowNumber) -> true)
                .isPresent();
    }

    private Inspection inspectLegacy(UUID group, boolean isUpgraded) {
        Counts counts = countInputs(group);
        var errors = new ArrayList<String>();
        long verified = 0;
        long reordered = 0;
        for (LegacyDocument document : loadLegacyDocuments(group, isUpgraded)) {
            try {
                if (validateLegacyDocument(document)) {
                    reordered++;
                }
                verified++;
            } catch (RawArchiveException failure) {
                errors.add(failure.getMessage() + ":" + document.id());
            }
        }
        return new Inspection(counts, verified, reordered, List.copyOf(errors));
    }

    private Counts countInputs(UUID group) {
        long messages = count("select count(*) from group_message", " where group_id=?", group);
        long documents =
                count(
                        "select count(*) from memory_document where kind='RAW'",
                        " and group_id=?",
                        group);
        long sources =
                count(
                        "select count(*) " + SEGMENT_REFERENCE + "where d.kind='RAW'",
                        " and d.group_id=?",
                        group);
        return new Counts(messages, documents, sources);
    }

    private long count(String statement, String groupFilter, UUID group) {
        return db.find(
                        statement + whenScoped(group, groupFilter),
                        (row, rowNumber) -> row.getLong(1),
                        groupArguments(group))
                .orElseThrow();
    }

    private List<LegacyDocument> loadLegacyDocuments(UUID group, boolean isUpgraded) {
        String storage = isUpgraded ? " and storage is not 'SEGMENT_V1'" : "";
        return db.query(
                DOCUMENT_COLUMNS
                        + " where kind='RAW'"
                        + storage
                        + whenScoped(group, " and group_id=?"),
                RawArchiveMigrationVerifier::readDocument,
                groupArguments(group));
    }

    List<LegacyDocument> loadMarkdownDocuments(RawPartition partition) {
        return db.query(
                DOCUMENT_COLUMNS
                        + " where group_id=? and epoch=? and kind='RAW' and storage='MARKDOWN' order by id",
                RawArchiveMigrationVerifier::readDocument,
                partition.groupId(),
                partition.epoch());
    }

    private static LegacyDocument readDocument(ResultSet row, int rowNumber) throws SQLException {
        UUID group = UUID.fromString(row.getString("group_id"));
        return new LegacyDocument(
                UUID.fromString(row.getString("id")),
                new RawPartition(group, row.getLong("epoch")),
                row.getLong("source_count"),
                "READY".equals(row.getString("state")));
    }

    boolean validateLegacyDocument(LegacyDocument document) {
        var rows =
                db.list(
                        """
                select m.* from memory_document_source s join group_message m on m.id=s.message_id
                where s.document_id=? order by m.event_seq limit ?
                """,
                        document.id(),
                        MAX_LEGACY_SOURCES + 1);
        requireLegacySourceCount(document, rows.size());
        for (var row : rows) {
            requireLegacySourceScope(document.partition(), row);
        }
        if (!document.isReady()) {
            return false;
        }
        String content = readLegacyMarkdown(path(document));
        List<String> blocks = rows.stream().map(RawRecord::from).map(RawRecord::markdown).toList();
        return validateLegacyContent(content, blocks);
    }

    private static void requireLegacySourceCount(LegacyDocument document, int sources) {
        if (sources > MAX_LEGACY_SOURCES) {
            throw new RawArchiveException(DOCUMENT_TOO_LARGE);
        }
        if (sources != document.sourceCount()) {
            throw new RawArchiveException("RAW_MIGRATION_SOURCE_MISSING");
        }
    }

    private static void requireLegacySourceScope(RawPartition partition, Map<String, Object> row) {
        if (!partition.groupId().equals(row.get("group_id"))
                || partition.epoch() != RawArchiveService.number(row, "epoch")
                || !"TEXT".equals(row.get("kind"))
                || row.get("event_seq") == null) {
            throw new RawArchiveException("RAW_MIGRATION_SOURCE_INVALID");
        }
    }

    Path path(LegacyDocument document) {
        return store.resolve(document.partition().groupId() + "/raw/" + document.id() + ".md");
    }

    private static String readLegacyMarkdown(Path file) {
        try {
            if (Files.size(file) > MAX_LEGACY_BYTES) {
                throw new RawArchiveException(DOCUMENT_TOO_LARGE);
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new RawArchiveException("RAW_MIGRATION_FILE_MISSING", failure);
        }
    }

    static boolean validateLegacyContent(String content, List<String> orderedBlocks) {
        if (!content.startsWith(RawRecord.MARKDOWN_TITLE)) {
            throw new RawArchiveException(CONTENT_MISMATCH);
        }
        var remaining = new ArrayList<>(orderedBlocks);
        int offset = RawRecord.MARKDOWN_TITLE.length();
        boolean isReordered = false;
        while (!remaining.isEmpty()) {
            int matched = requireMatchingBlock(content, offset, remaining);
            isReordered |= matched != 0;
            offset += remaining.remove(matched).length();
        }
        if (offset != content.length()) {
            throw new RawArchiveException(CONTENT_MISMATCH);
        }
        return isReordered;
    }

    private static int requireMatchingBlock(String content, int offset, List<String> blocks) {
        for (int index = 0; index < blocks.size(); index++) {
            if (content.startsWith(blocks.get(index), offset)) {
                return index;
            }
        }
        throw new RawArchiveException(CONTENT_MISMATCH);
    }

    private long verifySegments(UUID group) {
        var rows =
                db.list(
                        "select * from raw_archive_segment"
                                + whenScoped(group, " where group_id=?")
                                + " order by group_id,epoch,base_seq",
                        groupArguments(group));
        long records = 0;
        for (var row : rows) {
            RawSegment segment = RawSegment.from(row);
            store.streamVerified(
                    segment, record -> requireMatchingSource(segment.partition(), record));
            records += segment.recordCount();
        }
        return records;
    }

    private void requireMatchingSource(RawPartition partition, RawRecord record) {
        boolean isMatching =
                db.find(
                                "select * from group_message where id=? and group_id=? and epoch=?",
                                RawRecord::fromSqliteRow,
                                record.messageId(),
                                partition.groupId(),
                                partition.epoch())
                        .filter(record::equals)
                        .isPresent();
        if (!isMatching) {
            throw new RawArchiveException("RAW_VERIFY_SOURCE_MISMATCH");
        }
    }

    private long requireReferenceCoverage(UUID group, long records) {
        long sources =
                count(
                        "select count(*) "
                                + SEGMENT_REFERENCE
                                + "where d.storage='SEGMENT_V1' and s.kind='RAW'",
                        " and d.group_id=?",
                        group);
        if (records != sources) {
            throw new RawArchiveException("RAW_VERIFY_SOURCE_COUNT");
        }
        long missing =
                count(
                        "select count(*) "
                                + SEGMENT_REFERENCE
                                + """
                join group_message m on m.id=s.message_id where d.storage='SEGMENT_V1' and s.kind='RAW'
                and not exists(select 1 from raw_archive_ref r join raw_archive_segment a on a.id=r.segment_id
                    where r.document_id=d.id and a.group_id=m.group_id and a.epoch=m.epoch
                    and m.event_seq between a.base_seq and a.last_seq)
                """,
                        " and d.group_id=?",
                        group);
        if (missing != 0) {
            throw new RawArchiveException("RAW_VERIFY_REFERENCE");
        }
        return sources;
    }

    private static String whenScoped(UUID group, String clause) {
        return group == null ? "" : clause;
    }

    private static Object[] groupArguments(UUID group) {
        return group == null ? new Object[0] : new Object[] {group};
    }

    record LegacyDocument(UUID id, RawPartition partition, long sourceCount, boolean isReady) {}

    private record Counts(long messages, long documents, long sources) {}

    private record Inspection(Counts inputs, long verified, long reordered, List<String> errors) {
        Map<String, Object> serialize() {
            var report = new LinkedHashMap<String, Object>();
            report.put("messages", inputs.messages());
            report.put("rawDocuments", inputs.documents());
            report.put("sourceCount", inputs.sources());
            report.put("verifiedLegacyDocuments", verified);
            report.put("reorderedLegacyDocuments", reordered);
            report.put("errors", errors);
            report.put("ok", errors.isEmpty());
            return report;
        }
    }
}
