/**
 * [INPUT]: 提供冻结日期、来源 ID、覆盖元数据与待发布正文。
 * [OUTPUT]: 验证群内展示、报告失败与 Outbox 的原子发布。
 * [POS]: 日报发布和本地预览的共同契约测试。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import dev.qqbot.schedule.*;
import dev.qqbot.schedule.DailySummaryService.Snapshot;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Q02DailyPresentationTest {
    private final LocalDate date = LocalDate.of(2026, 11, 14);
    @TempDir Path directory;

    @Test
    void previewAndOutboxMatchExactlyAndAuditReferencesRemainInStorage() {
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("presentation.db"))) {
            var db = fixture.db;
            UUID group = fixture.group("preview");
            db.exec("update group_config set reply_enabled=true where id=?", group);
            var service =
                    new DailySummaryService(
                            db, Clock.fixed(Instant.parse("2026-11-15T04:00:00Z"), ZoneOffset.UTC));
            UUID id = service.trigger(group, "WEB");
            service.claim(id);
            // NO_DATA is terminal; use an explicitly running fixture to exercise the finish
            // transaction.
            db.exec("update daily_run set state='RUNNING',report=null where id=?", id);
            String ref = UUID.randomUUID().toString(),
                    report = "• 作业：周二提交。 [" + ref + "]\n• 讨论：已明确建系方法。 [" + ref + "]";
            var coverage =
                    Map.<String, Object>of(
                            "gaps",
                            List.of("known gap"),
                            "scopeNotice",
                            "detailed platform explanation");
            var header = new Snapshot.Header(id, group, 2, date);
            service.finish(
                    new DailySummaryService.Completion(
                            header, List.of(ref), db.json(coverage), report));
            var preview = DailySummaryPresentation.render(report, date, coverage);
            assertEquals(
                    preview,
                    db
                            .list(
                                    "select body from outbox where daily_run_id=? order by part_index",
                                    id)
                            .stream()
                            .map(r -> r.get("body"))
                            .toList());
            assertTrue(
                    ((String) db.one("select report from daily_run where id=?", id).get("report"))
                            .contains(ref));
            String body = String.join("", preview);
            assertTrue(body.contains("采集有缺口"));
            assertFalse(body.contains(ref));
            assertFalse(body.contains("管理端"));
            assertFalse(body.contains("platform"));
        }
    }

    @Test
    void splitIsLosslessAndNeverTruncatesOrSplitsSentences() {
        String paragraph = "• 进展：" + "实".repeat(220) + "。";
        String prose = String.join("\n", Collections.nCopies(4, paragraph));
        var parts = DailySummaryPresentation.render(prose, date, Map.of());
        assertTrue(parts.size() <= 3);
        assertTrue(parts.stream().allMatch(p -> DailySummaryPresentation.length(p) <= 500));
        assertEquals("昨日已采集要点 · " + date + "\n\n" + prose, String.join("", parts));
        assertFalse(String.join("", parts).contains("截断"));
        assertThrows(
                IllegalStateException.class,
                () -> DailySummaryPresentation.render("字".repeat(1678), date, Map.of()));
        assertThrows(
                IllegalStateException.class,
                () -> DailySummaryPresentation.render("字".repeat(600), date, Map.of()));
    }

    @Test
    void noisyOrOversizedReportCannotCommitGeneratedStateOrPartialOutbox() {
        try (var fixture = new MemoryV2TestDatabase(directory.resolve("atomic.db"))) {
            var db = fixture.db;
            UUID group = fixture.group("atomic");
            db.exec("update group_config set reply_enabled=true where id=?", group);
            var service =
                    new DailySummaryService(
                            db, Clock.fixed(Instant.parse("2026-11-15T04:00:00Z"), ZoneOffset.UTC));
            UUID id = service.trigger(group, "WEB");
            db.exec("update daily_run set state='RUNNING' where id=?", id);
            var header = new Snapshot.Header(id, group, 2, date);
            for (String invalid :
                    List.of(
                            "分享地址：https://example.com",
                            "出处[1]",
                            "访问example.com",
                            "分享链接：",
                            "长".repeat(1678))) {
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                service.finish(
                                        new DailySummaryService.Completion(
                                                header, List.of(), "{}", invalid)));
                var row = db.one("select state,report from daily_run where id=?", id);
                assertEquals("RUNNING", row.get("state"));
                assertNull(row.get("report"));
                assertTrue(db.list("select id from outbox where daily_run_id=?", id).isEmpty());
            }
        }
    }
}
