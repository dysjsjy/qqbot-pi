package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.memory.*;
import dev.qqbot.persistence.Db;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
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
            "bot.qq.enabled=false",
            "bot.weixin.enabled=false",
            "bot.pi.provider=test",
            "bot.pi.model=test",
            "bot.admin-password-hash=$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234"
        })
@ActiveProfiles("sqlite")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class S04RawHttpTest {
    @TempDir static Path directory;
    @Autowired MockMvc mvc;
    @Autowired Db db;
    @Autowired RawArchiveWorker worker;
    @Autowired RawArchiveService archive;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + directory.resolve("http.db"));
        registry.add("bot.state-dir", () -> directory.resolve("state").toString());
        registry.add("bot.env-file", () -> directory.resolve("missing.env").toString());
    }

    UUID group() {
        UUID id = UUID.randomUUID();
        db.exec(
                "insert into group_config(id,channel,account_id,external_id,name,enabled) values(?,'QQ','test',?,'测试群',true)",
                id,
                id.toString());
        return id;
    }

    void messages(UUID group, int after, int count) {
        db.tx(
                () -> {
                    for (int seq = after + 1; seq <= after + count; seq++) {
                        db.exec(
                                "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,received_at,epoch,kind,event_seq) values(?,?,?,'A','成员',?,?,?,0,'TEXT',?)",
                                UUID.randomUUID(),
                                group,
                                "key-" + seq,
                                "消息 " + seq,
                                Instant.now().minusSeconds(60),
                                Instant.now().minusSeconds(60),
                                seq);
                    }
                    db.exec("update group_config set event_seq=? where id=?", after + count, group);
                    return null;
                });
    }

    @Test
    void rawEndpointsRequireLogin() throws Exception {
        UUID group = group();
        mvc.perform(get("/api/admin/groups/" + group + "/memory/policy"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/groups/" + group + "/memory/documents/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void workerPublishesPagesAndClearInvalidatesEveryCursor() throws Exception {
        UUID group = group(), other = group();
        messages(group, 0, 205);
        worker.runOnce();
        String base = "/api/admin/groups/" + group + "/memory";
        UUID document =
                (UUID) db.one("select id from memory_document where group_id=?", group).get("id");
        mvc.perform(get(base + "/policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawArchive.archivedMessages").value(205))
                .andExpect(jsonPath("$.rawArchive.pendingMessages").value(0));
        mvc.perform(post(base + "/raw-archive/retry")).andExpect(status().isForbidden());
        mvc.perform(post(base + "/raw-archive/retry").with(csrf()))
                .andExpect(status().isAccepted());
        var response =
                mvc.perform(get(base + "/documents/" + document).param("limit", "100"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.storage").value("SEGMENT_V1"))
                        .andExpect(jsonPath("$.sources.length()").value(100))
                        .andExpect(jsonPath("$.totalSources").value(205))
                        .andReturn();
        String cursor =
                json.readTree(response.getResponse().getContentAsString())
                        .get("nextCursor")
                        .asText();
        messages(group, 205, 1);
        worker.runOnce();
        mvc.perform(
                        get(base + "/documents/" + document)
                                .param("limit", "200")
                                .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources.length()").value(105))
                .andExpect(jsonPath("$.snapshotSeq").value("205"))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        mvc.perform(
                        get("/api/admin/groups/" + other + "/memory/documents/" + document)
                                .param("cursor", cursor))
                .andExpect(status().isNotFound());
        mvc.perform(get(base + "/documents/" + document).param("cursor", "invalid"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        delete(base)
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"confirmName\":\"测试群\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(base + "/documents/" + document).param("cursor", cursor))
                .andExpect(status().isNotFound());
        assertEquals(0, archive.archiveBatch(group));
        assertTrue(db.list("select id from raw_archive_segment where group_id=?", group).isEmpty());
    }
}
