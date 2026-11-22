/**
 * [INPUT]: 使用临时 SQLite、固定时钟和本地分段文件。
 * [OUTPUT]: 验证热索引裁剪、冷资产读取、显式到期回收与群清除。
 * [POS]: 留存主链路冒烟；不访问生产状态或模型。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.management.GroupMembers;
import dev.qqbot.memory.*;
import java.nio.file.Path;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetentionSmokeTest {
    private static final Instant NOW = Instant.parse("2026-11-21T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    @TempDir Path directory;

    @Test
    void coldMessagesStayReadableWhileOnlyHotEvidenceAndFinitePolicyRemainOnline()
            throws Exception {
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("retention.db"));
                var store =
                        new RawSegmentStore(
                                directory, RawArchiveProperties.defaults(), new ObjectMapper())) {
            var db = fixture.db;
            var gateway = new BotProperties.Gateway(false, "", "", "test", "");
            var bot =
                    new BotProperties(
                            "admin",
                            "",
                            directory,
                            false,
                            new BotProperties.Pi(
                                    "node", "unused", "test", "test", "KEY", "", 5, ""),
                            new BotProperties.Embedding("", "", "", 0, 0),
                            new BotProperties.Qq(
                                    false, new BotProperties.OfficialQq("test", "", "", "", false)),
                            gateway,
                            new BotProperties.Rag(false));
            var group = fixture.group("retention");
            var cold =
                    fixture.message(
                            group,
                            "cold",
                            "member-old",
                            "旧日志独有",
                            1,
                            NOW.minus(Duration.ofDays(45)));
            var hot = fixture.message(group, "hot", "member-hot", "热日志独有", 2, NOW.minusSeconds(1));
            var files = new MarkdownMemory(db, bot);
            var index = new MemorySearchIndex(db, files, CLOCK);
            var oldIndex =
                    new MemorySearchIndex(db, files, Clock.offset(CLOCK, Duration.ofDays(-20)));
            oldIndex.indexPending(group, 10);
            var archive =
                    new MarkdownArchive(
                            db,
                            files,
                            new MemoryPolicyService(db),
                            MemoryProperties.defaults(),
                            CLOCK);
            var raw = new RawArchiveService(db, store, RawArchiveProperties.defaults(), CLOCK);
            archive.setRawArchive(raw);
            raw.archiveBatch(group);
            var report = java.util.UUID.randomUUID();
            db.exec(
                    "insert into daily_run(id,group_id,business_date,epoch,trigger_type,state,report,source_ids,created_at) values(?,?,?,2,'WEB','DELIVERED',?,?,?)",
                    report,
                    group,
                    LocalDate.of(2026, 10, 7),
                    "旧日报",
                    db.json(java.util.List.of(cold)),
                    NOW.minus(Duration.ofDays(44)));
            var summary = UUID.randomUUID();
            var activity = UUID.randomUUID();
            var old = NOW.minus(Duration.ofDays(44));
            db.exec(
                    "insert into memory_document(id,group_id,epoch,kind,state,source_count,created_at,finished_at) values(?,?,2,'SUMMARY','READY',1,?,?)",
                    summary,
                    group,
                    old,
                    old);
            db.exec(
                    "insert into memory_document_source(document_id,message_id,kind) values(?,?,'SUMMARY')",
                    summary,
                    cold);
            files.writeDocument(group, summary, "SUMMARY", "旧摘要");
            db.exec(
                    """
                insert into activity_run(id,group_id,business_date,epoch,snapshot_at,starts_at,ends_at,expires_at,top_n,publish_mode,state,delivery_state,ranking)
                values(?,?,?,2,?,?,?,?,5,'CONSOLE_ONLY','GENERATED','NOT_REQUESTED','[]')
                """,
                    activity,
                    group,
                    LocalDate.of(2026, 10, 7),
                    old,
                    old.minus(Duration.ofDays(1)),
                    old,
                    old.plus(Duration.ofDays(30)));
            var permanent = new MemoryMaintenance(db, bot, archive, index, CLOCK);
            permanent.cleanup();
            assertEquals(
                    "ARCHIVED",
                    db.one("select index_state from group_message where id=?", cold)
                            .get("index_state"));
            assertNull(
                    db.one(
                            "select id from memory_search_chunk where source_key=?",
                            "message:" + cold));
            assertEquals(
                    "旧日报", db.one("select report from daily_run where id=?", report).get("report"));
            assertEquals("旧摘要", archive.document(group, summary).get("content"));
            assertEquals(
                    "[]",
                    db.one("select ranking from activity_run where id=?", activity).get("ranking"));
            assertTrue(db.list("select id from raw_archive_gc").isEmpty());
            var document =
                    (UUID)
                            archive.documents(group, 10).stream()
                                    .filter(row -> "RAW".equals(row.get("kind")))
                                    .findFirst()
                                    .orElseThrow()
                                    .get("id");
            assertTrue(
                    archive.document(group, document).get("content").toString().contains("旧日志独有"));
            var members = new GroupMembers(db, CLOCK);
            assertEquals(
                    1,
                    ((Number)
                                    members.list(group, 90, "member-old", "messages", false, 25, 0)
                                            .get("total"))
                            .intValue());
            var admin =
                    new MemoryAdministration(
                            db, null, bot, permanent, RetentionProperties.defaults(), raw, CLOCK);
            var retained = admin.retention(group);
            assertEquals(2, retained.totalMessages());
            assertEquals(1, retained.hotMessages());
            assertTrue(retained.rawDiskBytes() > 0);

            new MemoryMaintenance(
                            db, bot, archive, index, CLOCK, new RetentionProperties(30, 40, 30))
                    .cleanup();
            new RawArchiveCollector(db, store, raw, CLOCK).runOnce();
            assertNull(db.one("select id from group_message where id=?", cold));
            assertNotNull(db.one("select id from group_message where id=?", hot));
            assertNull(db.one("select report from daily_run where id=?", report).get("report"));
            assertNull(db.one("select id from activity_run where id=?", activity));
            assertNull(db.one("select id from memory_document where id=?", summary));
            assertFalse(
                    archive.document(group, document).get("content").toString().contains("旧日志独有"));

            db.tx(
                    () -> {
                        permanent.clear(group);
                        return null;
                    });
            assertNull(db.one("select id from group_message where id=?", hot));
            assertTrue(archive.documents(group, 10).isEmpty());
        }
    }
}
