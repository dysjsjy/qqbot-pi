/**
 * [INPUT]: 接收已校验的原文归档、源 SQLite 库与新的目标目录。
 * [OUTPUT]: 写入 V9 副本的 Markdown、源映射和导出清单。
 * [POS]: 离线导出路径；分段迁移和源库校验留在 RawArchiveMigration。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import dev.qqbot.persistence.Db;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;

final class RawArchiveLegacyExport {
    private static final String DIRECTORY_INVALID = "RAW_EXPORT_DESTINATION_INVALID";
    private static final String EXPORT_IO = "RAW_EXPORT_IO";
    private static final int PAGE_SIZE = 100;

    private static final String READ_PAGE =
            """
            select m.* from memory_document_source s
            join group_message m on m.id=s.message_id
            join group_config g on g.id=m.group_id and g.epoch=m.epoch
            where s.document_id=? and s.kind='RAW' and m.epoch=? and m.group_id=?
            and m.kind='TEXT' and m.event_seq>? and m.occurred_at>=? and m.occurred_at<=?
            order by m.event_seq limit ?
            """;
    private static final String PRUNE_DOCUMENT =
            """
            delete from memory_document_source where document_id=? and message_id not in (
                select m.id from group_message m join group_config g on g.id=m.group_id and g.epoch=m.epoch
                where m.group_id=? and m.epoch=? and m.kind='TEXT' and m.occurred_at>=? and m.occurred_at<=?
            )
            """;

    private final Db source;
    private final RawSegmentStore store;
    private final RawArchiveService archive;
    private final Clock clock;

    RawArchiveLegacyExport(
            Db source, RawSegmentStore store, RawArchiveService archive, Clock clock) {
        this.source = source;
        this.store = store;
        this.archive = archive;
        this.clock = clock;
    }

    Map<String, Object> export(Path destination) {
        Path output = resolveDestination(destination);
        // SIDE EFFECT: 只写新目录；中断的副本不生成完成清单。
        createDestination(output);
        Db target = LegacySqliteCopy.copyVersionNine(source, output);
        List<Mapping> mappings = exportDocuments(target, output);
        copyVisibleMarkdown(target, output);
        verifyForeignKeys(target);
        return writeManifest(output, mappings);
    }

    private Path resolveDestination(Path destination) {
        Path absolute = destination.toAbsolutePath().normalize();
        if (absolute.getParent() == null) {
            throw invalidDestination(absolute);
        }
        Path output = resolveParent(absolute);
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                || output.startsWith(store.root())
                || store.root().startsWith(output)) {
            throw invalidDestination(output);
        }
        return output;
    }

    private static Path resolveParent(Path absolute) {
        try {
            return absolute.getParent().toRealPath().resolve(absolute.getFileName());
        } catch (IOException failure) {
            throw new RawArchiveException(DIRECTORY_INVALID, failure);
        }
    }

    private static RawArchiveException invalidDestination(Path path) {
        return new RawArchiveException(
                DIRECTORY_INVALID, new IllegalArgumentException(path.toString()));
    }

    private static void createDestination(Path output) {
        try {
            Files.createDirectory(output);
        } catch (FileAlreadyExistsException failure) {
            throw new RawArchiveException(DIRECTORY_INVALID, failure);
        } catch (IOException failure) {
            throw new RawArchiveException(EXPORT_IO, failure);
        }
    }

    private List<Mapping> exportDocuments(Db target, Path output) {
        List<LegacyDocument> documents =
                target.query(
                        """
                select id,group_id,epoch from memory_document where kind='RAW' order by group_id,epoch,id
                """,
                        RawArchiveLegacyExport::readDocument);
        var mappings = new ArrayList<Mapping>();
        for (LegacyDocument document : documents) {
            mappings.addAll(exportDocument(target, output, document));
        }
        return List.copyOf(mappings);
    }

    private static LegacyDocument readDocument(ResultSet row, int rowNumber) throws SQLException {
        UUID id = UUID.fromString(row.getString("id"));
        UUID group = UUID.fromString(row.getString("group_id"));
        return new LegacyDocument(id, new RawPartition(group, row.getLong("epoch")));
    }

    private List<Mapping> exportDocument(Db target, Path output, LegacyDocument document) {
        var mappings = new ArrayList<Mapping>();
        long after = 0;
        for (; ; ) {
            List<RawRecord> page = readPage(target, document, after);
            if (page.isEmpty()) {
                break;
            }
            boolean isFirstPage = mappings.isEmpty();
            UUID exported = isFirstPage ? document.id() : UUID.randomUUID();
            // SIDE EFFECT: 保留首页文档 ID，后续页在副本内建立独立文档。
            writeRawPage(output, document.partition(), exported, page);
            if (!isFirstPage) {
                target.tx(() -> moveSources(target, document, exported, page));
            }
            mappings.add(new Mapping(document.id().toString(), exported.toString(), page.size()));
            after = page.get(page.size() - 1).seq();
        }
        pruneDocument(target, document);
        return List.copyOf(mappings);
    }

    private List<RawRecord> readPage(Db target, LegacyDocument document, long after) {
        RawPartition partition = document.partition();
        return target.query(
                READ_PAGE,
                RawRecord::fromSqliteRow,
                document.id(),
                partition.epoch(),
                partition.groupId(),
                after,
                archive.archiveCutoff(clock.instant()),
                clock.instant(),
                PAGE_SIZE);
    }

    private static void writeRawPage(
            Path output, RawPartition partition, UUID exported, List<RawRecord> page) {
        Path group = output.resolve("memory").resolve(partition.groupId().toString());
        Path file = group.resolve("raw").resolve(exported + ".md");
        var content = new StringBuilder(RawRecord.MARKDOWN_TITLE);
        for (RawRecord record : page) {
            content.append(record.markdown());
        }
        writeNewFile(file, content);
    }

    private Void moveSources(
            Db target, LegacyDocument original, UUID exported, List<RawRecord> page) {
        target.exec(
                """
                insert into memory_document(id,group_id,epoch,kind,state,source_count,created_at,finished_at)
                values(?,?,?,'RAW','READY',?,?,?)
                """,
                exported,
                original.partition().groupId(),
                original.partition().epoch(),
                page.size(),
                clock.instant(),
                clock.instant());
        for (RawRecord record : page) {
            target.exec(
                    """
                    update memory_document_source set document_id=? where document_id=? and message_id=? and kind='RAW'
                    """,
                    exported,
                    original.id(),
                    record.messageId());
        }
        return null;
    }

    private void pruneDocument(Db target, LegacyDocument document) {
        UUID id = document.id();
        RawPartition partition = document.partition();
        target.exec(
                PRUNE_DOCUMENT,
                id,
                partition.groupId(),
                partition.epoch(),
                archive.archiveCutoff(clock.instant()),
                clock.instant());
        target.exec(
                """
                update memory_document set state='READY',error_code=null,
                    source_count=(select count(*) from memory_document_source where document_id=?) where id=?
                """,
                id,
                id);
        target.exec("delete from memory_document where id=? and source_count=0", id);
    }

    private void copyVisibleMarkdown(Db target, Path output) {
        List<String> groupFiles =
                target.query("select id from group_config", RawArchiveLegacyExport::memoryPath);
        for (String file : groupFiles) {
            copyIfVisible(file, output);
        }
        List<String> summaries =
                target.query(
                        """
                select id,group_id from memory_document where kind='SUMMARY' and state='READY'
                """,
                        RawArchiveLegacyExport::summaryPath);
        for (String file : summaries) {
            copyIfVisible(file, output);
        }
    }

    private static String memoryPath(ResultSet row, int rowNumber) throws SQLException {
        return row.getString("id") + "/MEMORY.md";
    }

    private static String summaryPath(ResultSet row, int rowNumber) throws SQLException {
        return row.getString("group_id") + "/summaries/" + row.getString("id") + ".md";
    }

    private void copyIfVisible(String relative, Path output) {
        Path file = store.resolve(relative);
        if (!Files.exists(file) || isTombstoned(relative)) {
            return;
        }
        Path target = output.resolve("memory").resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(file, target);
        } catch (IOException failure) {
            throw new RawArchiveException(EXPORT_IO, failure);
        }
    }

    private boolean isTombstoned(String relative) {
        return source.find(
                        "select 1 from memory_file_tombstone where relative_path=?",
                        (row, rowNumber) -> true,
                        relative)
                .isPresent();
    }

    private static void verifyForeignKeys(Db target) {
        if (!target.jdbc.queryForList("pragma foreign_key_check").isEmpty()) {
            throw new RawArchiveException("RAW_EXPORT_FOREIGN_KEY");
        }
    }

    private Map<String, Object> writeManifest(Path output, List<Mapping> mappings) {
        long messages = mappings.stream().mapToLong(Mapping::messages).sum();
        var serialized =
                Map.<String, Object>of(
                        "ok",
                        true,
                        "schemaVersion",
                        LegacySqliteCopy.SCHEMA_VERSION,
                        "rawDocuments",
                        (long) mappings.size(),
                        "rawMessages",
                        messages,
                        "mappings",
                        mappings.stream().map(Mapping::serialize).toList());
        writeNewFile(output.resolve("export-manifest.json"), source.json(serialized));
        return serialized;
    }

    private static void writeNewFile(Path file, CharSequence content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (IOException failure) {
            throw new RawArchiveException(EXPORT_IO, failure);
        }
    }

    private record LegacyDocument(UUID id, RawPartition partition) {}

    private record Mapping(String sourceDocument, String exportedDocument, int messages) {
        Map<String, Object> serialize() {
            return Map.of(
                    "sourceDocument",
                    sourceDocument,
                    "exportedDocument",
                    exportedDocument,
                    "messages",
                    messages);
        }
    }
}
