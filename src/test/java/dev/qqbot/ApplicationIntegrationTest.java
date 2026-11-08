package dev.qqbot;

import dev.qqbot.persistence.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"bot.scheduling=false","bot.qq.enabled=true","bot.qq.base-url=http://127.0.0.1:1","bot.qq.account-id=10001","bot.qq.token=token","bot.pi.provider=test","bot.pi.model=test","bot.embedding.url=http://127.0.0.1:1","bot.embedding.model=test","spring.datasource.hikari.maximum-pool-size=1"})
@AutoConfigureMockMvc
@WithMockUser(roles="ADMIN")
@EnabledIfEnvironmentVariable(named="TEST_DATABASE_URL",matches=".+")
class ApplicationIntegrationTest {
    @Autowired MockMvc mvc;@Autowired Db db;
    // PGlite's multiplexer cannot reproduce a second persistent session lock; lock validation needs real PG.
    @MockitoBean Lifecycle lifecycle;
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->System.getenv("TEST_DATABASE_URL"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
        r.add("spring.flyway.enabled",()->!"true".equals(System.getenv("TEST_DATABASE_PREMIGRATED")));
        r.add("bot.admin-password-hash",()->new BCryptPasswordEncoder().encode("test-password"));
    }
    @BeforeEach void reset(){db.exec("truncate group_config cascade");}
    String group(String name,String id){return "{\"channel\":\"QQ\",\"accountId\":\"10001\",\"externalId\":\""+id+"\",\"name\":\""+name+"\",\"enabled\":true,\"replyEnabled\":true,\"adminIds\":\"20001\",\"revision\":0}";}
    @Test @Transactional void createsGroupAndScheduleAndChecksRevisionAndImmutableScope()throws Exception{
        var result=mvc.perform(post("/api/admin/groups").with(csrf()).contentType("application/json").content(group("测试群","12345"))).andExpect(status().isOk()).andReturn();
        @SuppressWarnings("unchecked") var row=db.parse(result.getResponse().getContentAsString(),Map.class);String id=row.get("id").toString();
        mvc.perform(get("/api/admin/groups/"+id+"/schedule")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(patch("/api/admin/groups/"+id).with(csrf()).contentType("application/json").content(group("新名称","12345"))).andExpect(status().isOk());
        mvc.perform(patch("/api/admin/groups/"+id).with(csrf()).contentType("application/json").content(group("旧版本","12345"))).andExpect(status().isConflict());
        mvc.perform(patch("/api/admin/groups/"+id).with(csrf()).contentType("application/json").content(group("串群","23456"))).andExpect(status().isConflict());
        assertEquals("新名称",db.one("select name from group_config where id=?",UUID.fromString(id)).get("name"));
    }
    @Test void manualDailyTriggerIsIdempotentAndValidationRejectsInvalidTime()throws Exception{
        mvc.perform(post("/api/admin/groups").with(csrf()).contentType("application/json").content(group("测试群","12345"))).andExpect(status().isOk());
        String id=db.one("select id from group_config").get("id").toString();String first=null;
        for(int i=0;i<10;i++){var r=mvc.perform(post("/api/admin/groups/"+id+"/summary/yesterday").with(csrf())).andExpect(status().isOk()).andReturn();if(first==null)first=r.getResponse().getContentAsString();else assertEquals(first,r.getResponse().getContentAsString());}
        mvc.perform(put("/api/admin/groups/"+id+"/schedule").with(csrf()).contentType("application/json").content("{\"enabled\":true,\"localTime\":\"25:00\"}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/admin/groups/"+id+"/memory").with(csrf()).contentType("application/json").content("{\"confirmName\":\"错误的名称\"}")).andExpect(status().isConflict());
        assertEquals(1,((Number)db.one("select count(*) n from daily_run").get("n")).intValue());
    }
}
