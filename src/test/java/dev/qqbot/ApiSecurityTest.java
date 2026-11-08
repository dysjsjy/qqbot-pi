package dev.qqbot;

import dev.qqbot.admin.AdminController;
import dev.qqbot.channel.*;
import dev.qqbot.memory.MemoryService;
import dev.qqbot.message.IngestService;
import dev.qqbot.persistence.Db;
import dev.qqbot.schedule.DailySummaryService;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@WebMvcTest({AdminController.class,GroupIngressController.class,OneBotController.class})
@Import({SecurityConfig.class,LoginRateLimit.class})
@TestPropertySource(properties={"bot.scheduling=false","bot.qq.enabled=true","bot.qq.account-id=10001","bot.qq.token=test-token","bot.qq.webhook-secret=","bot.pi.provider=test","bot.pi.model=test"})
class ApiSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean Db db;
    @MockitoBean MemoryService memory;
    @MockitoBean DailySummaryService summary;
    @MockitoBean IngestService ingest;
    @MockitoBean ConnectionMonitor connections;
    @DynamicPropertySource static void admin(DynamicPropertyRegistry registry){registry.add("bot.admin-password-hash",()->new BCryptPasswordEncoder().encode("test-password"));}
    @Test void unauthenticatedReadRejected()throws Exception{mvc.perform(get("/api/admin/groups")).andExpect(status().isUnauthorized());verifyNoInteractions(db);}
    @Test @WithMockUser(roles="ADMIN") void writesRequireCsrf()throws Exception{mvc.perform(post("/api/admin/groups").contentType("application/json").content("{}")).andExpect(status().isForbidden());verifyNoInteractions(db);}
    @Test void sessionLoginAndLogout()throws Exception{
        var result=mvc.perform(post("/api/admin/login").with(csrf()).param("username","admin").param("password","test-password")).andExpect(status().isOk()).andReturn();
        var session=(org.springframework.mock.web.MockHttpSession)result.getRequest().getSession();
        mvc.perform(get("/api/admin/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("admin"));
        mvc.perform(post("/api/admin/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertTrue(session.isInvalid());
    }
    @Test void webhookRejectsUnauthenticatedAndWrongAccount()throws Exception{
        mvc.perform(post("/callbacks/qq/onebot").contentType("application/json").content("{\"self_id\":10001}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/callbacks/qq/onebot").header("Authorization","Bearer test-token").contentType("application/json").content("{\"self_id\":10002}")).andExpect(status().isForbidden());verifyNoInteractions(ingest);
    }
    @Test void onebotExtractsOnlyBotMentionAndExactText()throws Exception{
        when(ingest.ingest(any())).thenReturn(new IngestService.Receipt(null,null,false,true,false));
        String event="""
            {"post_type":"message","message_type":"group","self_id":10001,"group_id":12345,"user_id":20001,"message_id":5,"time":1794000000,"sender":{"nickname":"成员"},"message":[{"type":"at","data":{"qq":"10001"}},{"type":"text","data":{"text":" /help"}}]}
            """;
        mvc.perform(post("/callbacks/qq/onebot").header("Authorization","Bearer test-token").contentType("application/json").content(event)).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
        verify(ingest).ingest(argThat(r->r.mentionsBot()&&r.text().equals(" /help")&&r.groupId().equals("12345")&&r.accountId().equals("10001")));
    }
}
