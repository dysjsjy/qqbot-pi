/**
 * [INPUT]: 依赖 Spring HTTP、临时 SQLite 与真实业务服务。
 * [OUTPUT]: 验证群配置、成员 HTTP、定时与当前 epoch 的工作区统计。
 * [POS]: 管理模块数据库验收；只连接专用测试数据库。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.qqbot.persistence.*;
import dev.qqbot.schedule.DailyActivityService;
import dev.qqbot.schedule.DailySummaryService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
        properties = {
            "bot.scheduling=false",
            "bot.qq.enabled=true",
            "bot.qq.official.app-id=10001",
            "bot.qq.official.app-secret=test-secret",
            "bot.qq.official.transport=webhook",
            "bot.pi.provider=test",
            "bot.pi.model=test",
            "bot.embedding.url=http://127.0.0.1:1",
            "bot.embedding.model=test",
            "spring.datasource.hikari.maximum-pool-size=1"
        })
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationIntegrationTest {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path directory;
    @Autowired MockMvc mvc;
    @Autowired Db db;
    @Autowired DailyActivityService activity;
    @MockitoBean Lifecycle lifecycle;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + directory.resolve("application.db"));
        r.add("bot.admin-password-hash", () -> new BCryptPasswordEncoder().encode("test-password"));
    }

    @BeforeEach
    void reset() {
        db.exec("delete from group_config");
    }

    String group(String name, String id) {
        return "{\"channel\":\"QQ\",\"accountId\":\"10001\",\"externalId\":\""
                + id
                + "\",\"name\":\""
                + name
                + "\",\"enabled\":true,\"replyEnabled\":true,\"adminIds\":\"20001\",\"revision\":0}";
    }

    @Test
    @Transactional
    void retentionAndExperimentalProfileEndpointsKeepSafeDefaults() throws Exception {
        mvc.perform(
                        post("/api/admin/groups")
                                .with(csrf())
                                .contentType("application/json")
                                .content(group("留存冒烟群", "retention")))
                .andExpect(status().isOk());
        UUID id =
                (UUID)
                        db.one("select id from group_config where external_id='retention'")
                                .get("id");
        String root = "/api/admin/groups/" + id;
        mvc.perform(get(root + "/memory/retention"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archiveRetentionDays").value(0))
                .andExpect(jsonPath("$.hotMessages").value(0));
        String profiles = root + "/experimental/member-profiles";
        mvc.perform(get(profiles + "/policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.globalEnabled").value(false))
                .andExpect(jsonPath("$.policy.enabled").value(false));
        mvc.perform(
                        post(profiles + "/refresh")
                                .contentType("application/json")
                                .content("{\"mode\":\"INCREMENTAL\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(profiles + "/refresh")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"mode\":\"INCREMENTAL\"}"))
                .andExpect(status().isConflict());
        db.exec("update group_config set chat_type='PRIVATE',target_id=external_id where id=?", id);
        mvc.perform(get(profiles + "/list")).andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    void createsGroupAndScheduleAndChecksRevisionAndImmutableScope() throws Exception {
        var result =
                mvc.perform(
                                post("/api/admin/groups")
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(group("测试群", "12345")))
                        .andExpect(status().isOk())
                        .andReturn();
        @SuppressWarnings("unchecked")
        var row = db.parse(result.getResponse().getContentAsString(), Map.class);
        String id = row.get("id").toString();
        mvc.perform(get("/api/admin/groups/" + id + "/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(
                        patch("/api/admin/groups/" + id)
                                .with(csrf())
                                .contentType("application/json")
                                .content(group("新名称", "12345")))
                .andExpect(status().isOk());
        mvc.perform(
                        patch("/api/admin/groups/" + id)
                                .with(csrf())
                                .contentType("application/json")
                                .content(group("旧版本", "12345")))
                .andExpect(status().isConflict());
        mvc.perform(
                        patch("/api/admin/groups/" + id)
                                .with(csrf())
                                .contentType("application/json")
                                .content(group("串群", "23456")))
                .andExpect(status().isConflict());
        assertEquals(
                "新名称",
                db.one("select name from group_config where id=?", UUID.fromString(id))
                        .get("name"));
    }

    @Test
    @Transactional
    void manualDailyTriggerIsIdempotentAndValidationRejectsInvalidTime() throws Exception {
        mvc.perform(
                        post("/api/admin/groups")
                                .with(csrf())
                                .contentType("application/json")
                                .content(group("测试群", "12345")))
                .andExpect(status().isOk());
        String id = db.one("select id from group_config").get("id").toString();
        String first = null;
        for (int i = 0; i < 10; i++) {
            var r =
                    mvc.perform(post("/api/admin/groups/" + id + "/summary/yesterday").with(csrf()))
                            .andExpect(status().isOk())
                            .andReturn();
            if (first == null) {
                first = r.getResponse().getContentAsString();
            } else {
                assertEquals(first, r.getResponse().getContentAsString());
            }
        }
        mvc.perform(
                        put("/api/admin/groups/" + id + "/schedule")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"enabled\":true,\"localTime\":\"25:00\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        delete("/api/admin/groups/" + id + "/memory")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"confirmName\":\"错误的名称\"}"))
                .andExpect(status().isConflict());
        assertEquals(1, ((Number) db.one("select count(*) n from daily_run").get("n")).intValue());
    }

    @Test
    @Transactional
    void workspaceCountsOnlyCurrentEpochAndIncludesDailyRuns() throws Exception {
        mvc.perform(
                        post("/api/admin/groups")
                                .with(csrf())
                                .contentType("application/json")
                                .content(group("统计测试群", "12345")))
                .andExpect(status().isOk());
        UUID id = UUID.fromString(db.one("select id from group_config").get("id").toString());
        db.exec("update group_config set epoch=1 where id=?", id);
        for (int epoch = 0; epoch < 2; epoch++) {
            UUID message = UUID.randomUUID();
            db.exec(
                    "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,epoch,kind,index_state) values(?,?,?,'u1','成员','讨论内容',? ,?,'TEXT','PENDING')",
                    message,
                    id,
                    "epoch-" + epoch,
                    System.currentTimeMillis(),
                    epoch);
            db.exec(
                    "insert into memory_chunk(id,group_id,message_id,epoch,part_index,body,embedding,model,dimensions) values(?,?,?,?,0,'记忆','[1,0,0]','test',3)",
                    UUID.randomUUID(),
                    id,
                    message,
                    epoch);
        }
        db.exec(
                "insert into daily_run(id,group_id,business_date,epoch,trigger_type,state) values(?,?,current_date,1,'WEB','QUEUED')",
                UUID.randomUUID(),
                id);
        db.exec(
                "insert into daily_run(id,group_id,business_date,epoch,trigger_type,state) values(?,?,date('now','-1 day'),0,'WEB','RUNNING')",
                UUID.randomUUID(),
                id);
        db.exec("update daily_schedule set enabled=true where group_id=?", id);
        mvc.perform(get("/api/admin/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.registered_groups").value(1))
                .andExpect(jsonPath("$.counts.groups").value(1))
                .andExpect(jsonPath("$.counts.total_messages").value(1))
                .andExpect(jsonPath("$.counts.indexed_memories").value(1))
                .andExpect(jsonPath("$.counts.pending_index").value(1))
                .andExpect(jsonPath("$.counts.active_runs").value(1))
                .andExpect(jsonPath("$.counts.scheduled_groups").value(1))
                .andExpect(jsonPath("$.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.database").value("sqlite"))
                .andExpect(jsonPath("$.ragEnabled").value(false))
                .andExpect(jsonPath("$.gateways.QQ.accountId").value("10001"))
                .andExpect(jsonPath("$.gateways.QQ.transport").value("webhook"))
                .andExpect(jsonPath("$.gateways.QQ.appSecret").doesNotExist())
                .andExpect(jsonPath("$.gateways.QQ.token").doesNotExist());
    }

    @Test
    @Transactional
    void memberAndActivityEndpointsUseLiveSqliteContracts() throws Exception {
        mvc.perform(
                        post("/api/admin/groups")
                                .with(csrf())
                                .contentType("application/json")
                                .content(group("活动测试群", "activity")))
                .andExpect(status().isOk());
        UUID id =
                (UUID) db.one("select id from group_config where external_id='activity'").get("id");
        String root = "/api/admin/groups/" + id;
        LocalDate date = LocalDate.now(DailySummaryService.ZONE).minusDays(1);
        Instant occurred = date.atTime(12, 0).atZone(DailySummaryService.ZONE).toInstant();
        db.exec(
                "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,epoch,kind,event_seq) values(?,?,'source','activity-member','活跃成员','仅作夹具',?,0,'TEXT',1)",
                UUID.randomUUID(),
                id,
                occurred);
        mvc.perform(get(root + "/members").param("q", "活跃成员"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.members[0].messageCount").value(1))
                .andExpect(jsonPath("$.members[0].admin").value(false));
        mvc.perform(
                        put(root + "/members/admin")
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        """
                {"senderId":"activity-member","granted":true,"revision":0}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
        mvc.perform(get(root + "/members/detail").param("senderId", "activity-member"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member.admin").value(true))
                .andExpect(jsonPath("$.messages30d").value(1));
        mvc.perform(get(root + "/activity/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.publish_mode").value("CONSOLE_ONLY"));
        mvc.perform(
                        put(root + "/activity/schedule")
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        """
                {"enabled":true,"localTime":"00:00","topN":10,"publishMode":"CONSOLE_ONLY","revision":0}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.local_time").value("00:00:00"));
        db.exec("update activity_schedule set next_run_at=0 where group_id=?", id);
        activity.scheduleDue();
        mvc.perform(get(root + "/activity/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.runs[0].business_date").value(date.toString()))
                .andExpect(jsonPath("$.runs[0].ranking[0].nickname").value("活跃成员"))
                .andExpect(jsonPath("$.runs[0].delivery_state").value("NOT_REQUESTED"));
        db.exec("update daily_schedule set enabled=true where group_id=?", id);
        mvc.perform(get("/api/admin/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.scheduled_groups").value(1));
    }
}
