package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class S01RawMigrationSchemaTest {
    @TempDir Path directory;

    @Test
    void upgradingV9PreservesDocumentsAndGcSurvivesGroupDeletion() {
        Path path = directory.resolve("v9.db");
        UUID group, document = UUID.randomUUID();
        try (var old = new MemoryV2TestDatabase(path, "9")) {
            group = old.group("schema");
            old.db.exec(
                    "insert into memory_document(id,group_id,epoch,kind,state,source_count) values(?,?,2,'RAW','READY',0)",
                    document,
                    group);
        }
        try (var upgraded = new MemoryV2TestDatabase(path)) {
            assertEquals(
                    "MARKDOWN",
                    upgraded.db
                            .one("select storage from memory_document where id=?", document)
                            .get("storage"));
            dev.qqbot.memory.RawArchiveCollector.enqueue(
                    upgraded.db,
                    group,
                    2,
                    "DELETE_PARTITION",
                    java.util.Map.of("paths", java.util.List.of(group + "/raw-log/epoch-2")),
                    0);
            upgraded.db.exec("delete from group_config where id=?", group);
            assertEquals(1, upgraded.db.list("select id from raw_archive_gc").size());
        }
    }

    @Test
    void invalidConfigIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RawArchiveProperties(
                                true, 1, 86400, 1000, 500, 1048576, 4096, 16, 60000));
        assertTrue(RawArchiveProperties.defaults().enabled());
    }
}
