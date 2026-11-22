package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.qqbot.persistence.*;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "bot.scheduling=false",
            "bot.rag.enabled=false",
            "bot.pi.provider=test",
            "bot.pi.model=test",
            "bot.embedding.url=",
            "bot.embedding.model=",
            "bot.qq.enabled=true",
            "bot.qq.official.app-id=10001",
            "bot.qq.official.app-secret=test-secret",
            "bot.qq.official.transport=webhook",
            "bot.admin-password-hash=$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234"
        })
@ActiveProfiles("sqlite")
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SQLiteApplicationIntegrationTest {
    @TempDir static Path directory;
    @Autowired MockMvc mvc;
    @Autowired Db db;
    @Autowired BotProperties props;
    @Autowired DataSource source;
    @Autowired dev.qqbot.memory.MemorySearchWorker memorySearchWorker;
    @Autowired dev.qqbot.memory.RawArchiveService rawArchive;
    @Autowired dev.qqbot.memory.RawArchiveCollector collector;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:sqlite:" + directory.resolve("application.db"));
        registry.add("bot.state-dir", () -> directory.resolve("state").toString());
    }

    @Test
    void discoveredGroupWithoutScheduleGetsDefaultsAndCanSaveThem() throws Exception {
        UUID id = UUID.randomUUID();
        db.exec(
                "insert into group_config(id,channel,account_id,external_id,name,enabled) values(?,'QQ','10001','discovered','自动发现群',true)",
                id);
        mvc.perform(get("/api/admin/groups/" + id + "/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.local_time").value("09:00:00"))
                .andExpect(jsonPath("$.zone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.next_run_at").exists());
        assertNull(db.one("select * from daily_schedule where group_id=?", id));
        mvc.perform(
                        put("/api/admin/groups/" + id + "/schedule")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"enabled\":true,\"localTime\":\"22:30\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        assertNotNull(db.one("select * from daily_schedule where group_id=?", id));
        mvc.perform(get("/api/admin/groups/" + UUID.randomUUID() + "/schedule"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("群配置不存在，请刷新群列表"));
        mvc.perform(get("/api/admin/runs/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("请求的记录不存在，可能已被删除，请刷新列表"));
    }

    @Test
    void markdownPolicyDocumentsAndSearchWorkWithoutRag() throws Exception {
        UUID id = UUID.randomUUID(), other = UUID.randomUUID();
        db.exec(
                "insert into group_config(id,channel,account_id,external_id,name,enabled) values(?,'QQ','10001','markdown-api','记忆群',true)",
                id);
        db.exec(
                "insert into group_config(id,channel,account_id,external_id,name,enabled) values(?,'QQ','10001','markdown-other','另一个群',true)",
                other);
        String base = "/api/admin/groups/" + id + "/memory";
        mvc.perform(get(base + "/policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("RAW"))
                .andExpect(jsonPath("$.batchSize").value(100));
        mvc.perform(
                        put(base + "/policy")
                                .contentType("application/json")
                                .content("{\"mode\":\"SUMMARY\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        put(base + "/policy")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"mode\":\"invalid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("记忆模式须为 RAW 或 SUMMARY"));
        mvc.perform(
                        put(base + "/policy")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"mode\":\"SUMMARY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SUMMARY"));
        mvc.perform(
                        put(base + "/markdown")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"content\":\"# 发布安排\\n周五上线，负责人张三\"}"))
                .andExpect(status().isOk());
        memorySearchWorker.tick();
        mvc.perform(
                        post(base + "/search")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"question\":\"发布安排\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("MEMORY.md"));
        UUID document = UUID.randomUUID();
        db.exec(
                "insert into memory_document(id,group_id,epoch,kind,state,source_count) values(?,?,0,'SUMMARY','FAILED',100)",
                document,
                id);
        mvc.perform(get(base + "/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("FAILED"));
        mvc.perform(get(base + "/documents/" + document))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(""));
        mvc.perform(get("/api/admin/groups/" + other + "/memory/documents/" + document))
                .andExpect(status().isNotFound());
        mvc.perform(post(base + "/documents/" + document + "/retry").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("QUEUED"));
        mvc.perform(post(base + "/documents/" + document + "/retry").with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void retryEndpointSupportsRepeatedAnalysisAndDeliveryFailures() throws Exception {
        UUID groupId = UUID.randomUUID(), runId = UUID.randomUUID(), partId = UUID.randomUUID();
        db.exec(
                "insert into group_config(id,channel,account_id,external_id,name,enabled) values(?,'QQ','10001','retry-group','重试群',true)",
                groupId);
        db.exec(
                "insert into daily_run(id,group_id,business_date,epoch,trigger_type,state,attempt) values(?,?,'2026-11-01',0,'WEB','FAILED',3)",
                runId,
                groupId);
        mvc.perform(post("/api/admin/runs/" + runId + "/retry").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("QUEUED"));
        assertEquals(
                4,
                ((Number) db.one("select attempt from daily_run where id=?", runId).get("attempt"))
                        .intValue());
        db.exec("update daily_run set state='DELIVERY_FAILED',report='已生成报告' where id=?", runId);
        db.exec(
                "insert into outbox(id,group_id,result_key,daily_run_id,epoch,part_index,body,state) values(?,?,?, ?,0,0,'已生成报告','FAILED')",
                partId,
                groupId,
                "daily:" + runId,
                runId);
        mvc.perform(post("/api/admin/runs/" + runId + "/retry").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("GENERATED"));
        assertEquals("PENDING", db.one("select state from outbox where id=?", partId).get("state"));
        db.exec("update daily_run set state='DELIVERY_UNKNOWN' where id=?", runId);
        mvc.perform(post("/api/admin/runs/" + runId + "/retry").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("发送结果未知，请先核实群内是否收到，不能直接重试以免重复发送"));
    }

    @Test
    void startsWithoutEmbeddingAndAdminCrudUsesLocalDatabase() throws Exception {
        mvc.perform(get("/api/admin/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ragEnabled").value(false))
                .andExpect(jsonPath("$.database").value("sqlite"));
        String group =
                "{\"channel\":\"QQ\",\"accountId\":\"10001\",\"externalId\":\"12345\",\"name\":\"本地群\",\"enabled\":true,\"replyEnabled\":true,\"revision\":0}";
        mvc.perform(
                        post("/api/admin/groups")
                                .with(csrf())
                                .contentType("application/json")
                                .content(group))
                .andExpect(status().isOk());
        String id =
                db.one("select id from group_config where external_id='12345'")
                        .get("id")
                        .toString();
        mvc.perform(get("/api/admin/groups/" + id + "/memory/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.indexed").value(0))
                .andExpect(jsonPath("$.last_indexed").isEmpty());
        mvc.perform(get("/api/admin/groups/" + id + "/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(
                        put("/api/admin/groups/" + id + "/schedule")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"enabled\":true,\"localTime\":\"10:30\"}"))
                .andExpect(status().isOk());
        mvc.perform(
                        patch("/api/admin/groups/" + id)
                                .with(csrf())
                                .contentType("application/json")
                                .content(group.replace("本地群", "改名")))
                .andExpect(status().isOk());
        mvc.perform(
                        patch("/api/admin/groups/" + id)
                                .with(csrf())
                                .contentType("application/json")
                                .content(group))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/groups/" + id + "/summary/yesterday").with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/runs")).andExpect(status().isOk());
        try (var connection = source.getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("pragma foreign_keys")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
        try (var second =
                        java.sql.DriverManager.getConnection(
                                "jdbc:sqlite:" + directory.resolve("application.db"));
                var statement = second.createStatement();
                var rows =
                        statement.executeQuery(
                                "select name from group_config where external_id='12345'")) {
            assertTrue(rows.next());
            assertEquals("改名", rows.getString(1));
        }
        mvc.perform(
                        delete("/api/admin/groups/" + id + "/memory")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"confirmName\":\"改名\"}"))
                .andExpect(status().isOk());
        assertEquals(
                1L,
                db.one("select epoch from group_config where id=?", UUID.fromString(id))
                        .get("epoch"));
    }

    @Test
    void deleteChecksNameRevisionCsrfAndRemovesAllRelatedDataWhileKeepingOtherGroupsAndAudit()
            throws Exception {
        var id = UUID.randomUUID();
        var other = UUID.randomUUID();
        var message = UUID.randomUUID();
        var conversation = UUID.randomUUID();
        var run = UUID.randomUUID();
        var daily = UUID.randomUUID();
        for (var group : java.util.List.of(id, other)) {
            db.exec(
                    "insert into group_config(id,channel,account_id,external_id,name,enabled,revision) values(?,'QQ','10001',?,'待删除群',true,3)",
                    group,
                    group.equals(id) ? "22222" : "33333");
        }
        db.exec(
                "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,epoch,kind,event_seq) values(?,?,'source','member','成员','text',cast(unixepoch('subsec')*1000 as integer),0,'TEXT',1)",
                message,
                id);
        db.exec("update group_config set event_seq=1 where id=?", id);
        db.exec(
                "insert into memory_chunk(id,group_id,message_id,epoch,part_index,body,embedding,model,dimensions) values(?,?,?,0,0,'body','[1]','test',1)",
                UUID.randomUUID(),
                id,
                message);
        db.exec(
                "insert into conversation(id,group_id,sender_id) values(?,?,'member')",
                conversation,
                id);
        db.exec(
                "insert into agent_run(id,group_id,message_id,conversation_id,generation,epoch,kind,prompt,state) values(?,?,?,?,0,0,'CHAT','prompt','RUNNING')",
                run,
                id,
                message,
                conversation);
        db.exec(
                "insert into daily_run(id,group_id,business_date,epoch,trigger_type) values(?,?,'2026-11-01',0,'WEB')",
                daily,
                id);
        db.exec(
                "insert into outbox(id,group_id,result_key,agent_run_id,daily_run_id,conversation_id,epoch,part_index,body) values(?,?,'delete-fixture',?,?,?,0,0,'queued')",
                UUID.randomUUID(),
                id,
                run,
                daily,
                conversation);
        db.exec(
                "insert into daily_schedule(group_id,next_run_at) values(?,cast(unixepoch('subsec')*1000 as integer))",
                id);
        db.exec(
                "insert into collection_gap(id,group_id,starts_at,reason) values(?,?,cast(unixepoch('subsec')*1000 as integer),'TEST')",
                UUID.randomUUID(),
                id);
        rawArchive.archiveBatch(id);
        var memoryDirectory = props.stateDir().resolve("memory").resolve(id.toString());
        assertTrue(java.nio.file.Files.exists(memoryDirectory));
        mvc.perform(get("/api/admin/groups/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message_count").value(1));
        mvc.perform(
                        delete("/api/admin/groups/" + id)
                                .contentType("application/json")
                                .content("{\"confirmName\":\"待删除群\",\"revision\":3}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        delete("/api/admin/groups/" + id)
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"confirmName\":\"wrong\",\"revision\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("确认名称不匹配"));
        mvc.perform(
                        delete("/api/admin/groups/" + id)
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"confirmName\":\"待删除群\",\"revision\":2}"))
                .andExpect(status().isConflict());
        assertNotNull(db.one("select id from group_config where id=?", id));
        mvc.perform(
                        delete("/api/admin/groups/" + id)
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"confirmName\":\"待删除群\",\"revision\":3}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/groups/" + id)).andExpect(status().isNotFound());
        assertEquals(
                404,
                assertThrows(
                                org.springframework.web.server.ResponseStatusException.class,
                                () -> rawArchive.status(id))
                        .getStatusCode()
                        .value());
        collector.runOnce();
        try (var files = java.nio.file.Files.walk(memoryDirectory)) {
            assertEquals(0, files.filter(java.nio.file.Files::isRegularFile).count());
        }
        assertNull(db.one("select id from memory_document where group_id=?", id));
        for (String table :
                java.util.List.of(
                        "outbox",
                        "agent_run",
                        "daily_run",
                        "daily_schedule",
                        "memory_chunk",
                        "group_message",
                        "conversation",
                        "collection_gap")) {
            assertEquals(
                    0,
                    ((Number)
                                    db.one(
                                                    "select count(*) n from "
                                                            + table
                                                            + " where group_id=?",
                                                    id)
                                            .get("n"))
                            .intValue(),
                    table);
        }
        assertNotNull(db.one("select id from group_config where id=?", other));
        assertNotNull(
                db.one(
                        "select id from admin_audit where action='GROUP_DELETE' and target=?",
                        id.toString()));
        mvc.perform(get("/api/admin/audit")).andExpect(status().isOk());
        var logger = org.slf4j.LoggerFactory.getLogger("dev.qqbot.diagnostics");
        logger.warn("fixture API key={} Bearer dynamic-value", props.qq().official().appSecret());
        var logs =
                mvc.perform(get("/api/admin/logs?level=WARN&query=fixture"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items[0].level").value("WARN"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertTrue(logs.contains("[REDACTED]"));
        assertFalse(logs.contains("dynamic-value"));
        assertFalse(logs.contains("test-secret"));
    }
}
