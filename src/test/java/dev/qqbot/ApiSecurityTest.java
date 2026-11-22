/**
 * [INPUT]: 依赖管理 HTTP、平台入口、MockMvc 与 Spring Security。
 * [OUTPUT]: 验证会话、CSRF 和桥接账号隔离。
 * [POS]: 写接口安全回归；不访问生产环境。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.qqbot.admin.*;
import dev.qqbot.channel.*;
import dev.qqbot.management.*;
import dev.qqbot.memory.MemoryAdministration;
import dev.qqbot.message.IngestService;
import dev.qqbot.message.ReplyPolicyService;
import dev.qqbot.schedule.ActivityScheduleService;
import dev.qqbot.schedule.DailyActivityService;
import dev.qqbot.schedule.DailySummaryService;
import dev.qqbot.schedule.ScheduleSettingsService;
import dev.qqbot.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({
    SystemController.class,
    GroupAdminController.class,
    MemoryAdminController.class,
    ScheduleAdminController.class,
    ActivityAdminController.class,
    ReplyPolicyController.class,
    RunAdminController.class,
    GroupIngressController.class
})
@Import({SecurityConfig.class, LoginRateLimit.class})
@TestPropertySource(
        properties = {
            "bot.scheduling=false",
            "bot.qq.enabled=false",
            "bot.weixin.enabled=true",
            "bot.weixin.account-id=10001",
            "bot.weixin.token=test-token",
            "bot.qq.official.app-id=10001",
            "bot.qq.official.app-secret=test-secret",
            "bot.qq.official.transport=webhook",
            "bot.pi.provider=test",
            "bot.pi.model=test"
        })
class ApiSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean GroupAdministration groups;
    @MockitoBean GroupMembers members;
    @MockitoBean ManagementQueries queries;
    @MockitoBean MemoryAdministration memory;
    @MockitoBean ScheduleSettingsService schedules;
    @MockitoBean DailySummaryService summary;
    @MockitoBean ActivityScheduleService activity;
    @MockitoBean DailyActivityService rankings;
    @MockitoBean ReplyPolicyService policies;
    @MockitoBean IngestService ingest;
    @MockitoBean ConnectionMonitor connections;

    @DynamicPropertySource
    static void admin(DynamicPropertyRegistry registry) {
        registry.add(
                "bot.admin-password-hash",
                () -> new BCryptPasswordEncoder().encode("test-password"));
    }

    @Test
    void unauthenticatedReadRejected() throws Exception {
        mvc.perform(get("/api/admin/groups")).andExpect(status().isUnauthorized());
        verifyNoInteractions(groups, queries);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void writesRequireCsrf() throws Exception {
        mvc.perform(post("/api/admin/groups").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(groups);
    }

    @Test
    void activityAndMembersAreNotPublic() throws Exception {
        String group = "/api/admin/groups/00000000-0000-0000-0000-000000000001";
        for (String suffix :
                new String[] {
                    "/members",
                    "/members/detail?senderId=member",
                    "/activity/runs",
                    "/activity/schedule",
                    "/reply-policy"
                }) {
            mvc.perform(get(group + suffix)).andExpect(status().isUnauthorized());
        }
        mvc.perform(
                        put(group + "/members/admin")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(groups, members, activity, rankings, policies);
    }

    @Test
    @WithMockUser(username = "operator", roles = "ADMIN")
    void groupWritesVerifyCsrfAndPassTheActor() throws Exception {
        var id = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        String group = "/api/admin/groups/" + id;
        for (String suffix :
                new String[] {"/members/admin", "/activity/schedule", "/reply-policy"}) {
            mvc.perform(put(group + suffix).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(groups, activity, policies);
        mvc.perform(
                        put(group + "/members/admin")
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        """
            {"senderId":"member","granted":true,"revision":4}
            """))
                .andExpect(status().isOk());
        verify(groups).setMemberAdmin(id, "member", true, 4, "operator");
        mvc.perform(
                        put(group + "/reply-policy")
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        """
            {"mode":"ENERGY","probability":0.25,"energyGain":2,"energyCost":6,"revision":3}
            """))
                .andExpect(status().isOk());
        verify(policies)
                .save(
                        id,
                        new ReplyPolicyService.Patch("ENERGY", 0.25, 2, 6),
                        3L,
                        "WEB",
                        "operator");
        mvc.perform(
                        put(group + "/activity/schedule")
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        """
            {"enabled":true,"localTime":"09:00","topN":21,"publishMode":"CONSOLE_ONLY","revision":0}
            """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(activity);
    }

    @Test
    void sessionLoginAndLogout() throws Exception {
        var result =
                mvc.perform(
                                post("/api/admin/login")
                                        .with(csrf())
                                        .param("username", "admin")
                                        .param("password", "test-password"))
                        .andExpect(status().isOk())
                        .andReturn();
        var session =
                (org.springframework.mock.web.MockHttpSession) result.getRequest().getSession();
        mvc.perform(get("/api/admin/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"));
        mvc.perform(post("/api/admin/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertTrue(session.isInvalid());
    }

    @Test
    void genericGroupIngressCannotCreatePrivateScope() throws Exception {
        String body =
                """
            {"channel":"WEIXIN","accountId":"10001","groupId":"20001","messageId":"private","senderId":"20001","senderName":"用户","occurredAt":"2026-11-10T12:00:00Z","text":"你好","privateChat":true}
            """;
        mvc.perform(
                        post("/api/ingress/weixin")
                                .header("X-Bot-Token", "test-token")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ingest);
    }
}
