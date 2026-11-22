/**
 * [INPUT]: 使用隔离 SQLite、管理会话和固定用量账目。
 * [OUTPUT]: 验证管理接口的状态、权限和统计字段契约。
 * [POS]: 管理台记忆 HTTP 回归；旧统计补零画像层。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.model.*;
import dev.qqbot.persistence.Db;
import dev.qqbot.usage.ModelCallStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
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
            "bot.qq.enabled=false",
            "bot.weixin.enabled=false",
            "bot.admin-password-hash=$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234",
            "bot.memory.input-price=1",
            "bot.memory.output-price=2",
            "bot.memory.cache-read-price=0.1",
            "bot.memory.cache-write-price=1.25",
            "bot.memory.price-version=test-v1"
        })
@ActiveProfiles("sqlite")
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(T09AdminTest.FixedTime.class)
class T09AdminTest {
    private static final Instant NOW = Instant.parse("2026-11-11T16:30:00Z");
    @TempDir static Path directory;
    @Autowired MockMvc mvc;
    @Autowired Db db;
    @Autowired ObjectMapper json;
    @Autowired ModelCallStore calls;

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock memoryAdminTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + directory.resolve("admin.db"));
        registry.add("bot.state-dir", () -> directory.resolve("state").toString());
    }

    @Test
    void A01AllReadsRequireLoginAndSettingsWriteRequiresCsrf() throws Exception {
        UUID group = group();
        String base = base(group);
        for (String path :
                List.of("/settings", "/usage", "/context-stats?runId=" + UUID.randomUUID())) {
            mvc.perform(get(base + path).with(anonymous())).andExpect(status().isUnauthorized());
        }
        mvc.perform(
                        put(base + "/settings")
                                .contentType("application/json")
                                .content(json.writeValueAsString(settings(0))))
                .andExpect(status().isForbidden());
        assertEquals(
                0L,
                ((Number)
                                db.one(
                                                "select count(*) n from memory_policy where group_id=?",
                                                group)
                                        .get("n"))
                        .longValue());
    }

    @Test
    void A02EveryMissingNullOrInvalidFieldRejectsWithoutMutation() throws Exception {
        UUID group = group();
        String before = read(base(group) + "/settings").toString();
        for (String field : settings(0).keySet()) {
            var missing = settings(0);
            missing.remove(field);
            reject(group, missing, before);
            var empty = settings(0);
            empty.put(field, null);
            reject(group, empty, before);
        }
        var invalid =
                Map.<String, List<Object>>of(
                        "revision",
                        List.of(-1),
                        "mode",
                        List.of("other"),
                        "strategyVersion",
                        List.of(0, 3),
                        "summaryTriggerTokens",
                        List.of(1023, 16001),
                        "summaryCooldownMinutes",
                        List.of(4, 1441));
        for (var entry : invalid.entrySet()) {
            for (Object value : entry.getValue()) {
                var input = settings(0);
                input.put(entry.getKey(), value);
                reject(group, input, before);
            }
        }
        mvc.perform(
                        put(base(group) + "/settings")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString());
        assertEquals(before, read(base(group) + "/settings").toString());
    }

    @Test
    void A02AllInclusiveBoundariesSaveAndDefaultsAreExact() throws Exception {
        UUID group = group();
        assertEquals(
                json.readTree(json.writeValueAsString(settings(0))),
                read(base(group) + "/settings"));
        long revision = 0;
        for (int trigger : List.of(1024, 16000)) {
            for (int cooldown : List.of(5, 1440)) {
                var input = settings(revision);
                input.put("summaryTriggerTokens", trigger);
                input.put("summaryCooldownMinutes", cooldown);
                var result = save(group, input);
                assertEquals(++revision, result.get("revision").asLong());
                assertEquals(trigger, result.get("summaryTriggerTokens").asInt());
                assertEquals(cooldown, result.get("summaryCooldownMinutes").asInt());
            }
        }
    }

    @Test
    void A03StaleRevisionPreservesEntireSavedObject() throws Exception {
        UUID group = group();
        var first = settings(0);
        first.put("strategyVersion", 2);
        var saved = save(group, first);
        var second = settings(0);
        second.put("mode", "SUMMARY");
        mvc.perform(
                        put(base(group) + "/settings")
                                .with(csrf())
                                .contentType("application/json")
                                .content(json.writeValueAsString(second)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("MEMORY_SETTINGS_CONFLICT"));
        assertEquals(saved, read(base(group) + "/settings"));
    }

    @Test
    void A04LegacyPolicyPreservesNewSettingsAndResponseShape() throws Exception {
        UUID group = group();
        var input = settings(0);
        input.put("strategyVersion", 2);
        input.put("summaryTriggerTokens", 7000);
        input.put("summaryCooldownMinutes", 60);
        save(group, input);
        mvc.perform(
                        put(base(group) + "/policy")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"mode\":\"SUMMARY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SUMMARY"))
                .andExpect(jsonPath("$.batchSize").value(100))
                .andExpect(jsonPath("$.counts").exists())
                .andExpect(jsonPath("$.uncompressed").exists());
        var actual = read(base(group) + "/settings");
        input.put("mode", "SUMMARY");
        input.put("revision", 2L);
        assertEquals(json.readTree(json.writeValueAsString(input)), actual);
        mvc.perform(
                        put(base(group) + "/policy")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"mode\":\"invalid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("记忆模式须为 RAW 或 SUMMARY"));
    }

    @Test
    void A05UnknownGroupsRunsAndUnreadyStatsHaveDistinctErrors() throws Exception {
        UUID group = group(), other = group(), run = run(other, null, NOW, 0);
        for (String path : List.of("/settings", "/usage", "/context-stats?runId=" + run)) {
            mvc.perform(get(base(UUID.randomUUID()) + path))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").isString());
        }
        mvc.perform(get(base(group) + "/context-stats?runId=" + run))
                .andExpect(status().isNotFound());
        mvc.perform(get(base(group) + "/context-stats?runId=" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
        UUID unready = run(group, null, NOW, 0);
        mvc.perform(get(base(group) + "/context-stats?runId=" + unready))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("CONTEXT_NOT_READY"));
        mvc.perform(get(base(group) + "/context-stats?runId=invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void A05LatestStatsAreCurrentEpochAndReturnOnlySixSafeFields() throws Exception {
        UUID group = group();
        var stats =
                Map.of(
                        "estimatedInputTokens",
                        300,
                        "layerTokens",
                        Map.of(
                                "system",
                                20,
                                "current",
                                10,
                                "manual",
                                4,
                                "history",
                                6,
                                "evidence",
                                4,
                                "summary",
                                0,
                                "overhead",
                                256),
                        "sourceCount",
                        1,
                        "manualHash",
                        "hash",
                        "reasons",
                        List.of("TOTAL_BUDGET"));
        UUID previous = run(group, stats, NOW.minusSeconds(2), 0),
                latest = run(group, stats, NOW.minusSeconds(1), 0);
        run(group, stats, NOW, 1);
        run(group, null, NOW.plusSeconds(1), 0);
        run(group(), stats, NOW.plusSeconds(2), 0);
        var result = read(base(group) + "/context-stats");
        assertEquals(latest.toString(), result.get("runId").asText());
        var fields = new HashSet<String>();
        result.fieldNames().forEachRemaining(fields::add);
        assertEquals(
                Set.of(
                        "runId",
                        "estimatedInputTokens",
                        "layerTokens",
                        "sourceCount",
                        "manualHash",
                        "reasons"),
                fields);
        assertEquals(8, result.get("layerTokens").size());
        assertEquals(0, result.get("layerTokens").get("profile").intValue());
        assertEquals(1, result.get("sourceCount").asInt());
        assertEquals(
                previous.toString(),
                read(base(group) + "/context-stats?runId=" + previous).get("runId").asText());
        mvc.perform(get(base(group()) + "/context-stats")).andExpect(status().isConflict());
    }

    @Test
    void A06UsageUsesRealLedgerExactAmountsAndShanghaiToday() throws Exception {
        UUID group = group();
        var reported = request(group, Purpose.CHAT);
        calls.begin(reported);
        calls.dispatched(reported.callId());
        calls.usage(
                reported.callId(), new ModelUsage(100L, 10L, 20L, 0L, 5L, UsageStatus.REPORTED));
        calls.finish(reported.callId(), "SUCCEEDED", null);
        var unknown = request(group, Purpose.MEMORY_QUERY);
        calls.begin(unknown);
        calls.dispatched(unknown.callId());
        calls.finish(unknown.callId(), "FAILED", "TIMEOUT");
        var busy = request(group, Purpose.MEMORY_SUMMARY);
        calls.begin(busy);
        calls.finish(busy.callId(), "FAILED", "BACKGROUND_BUSY");
        var outside = request(group(), Purpose.CHAT);
        calls.begin(outside);
        var result = read(base(group) + "/usage");
        assertEquals("2026-11-12", result.get("date").asText());
        assertEquals("Asia/Shanghai", result.get("zone").asText());
        assertEquals("GENERATION_ONLY", result.get("coverage").asText());
        assertEquals(3, result.get("calls").asInt());
        assertEquals(2, result.get("dispatchedCalls").asInt());
        assertEquals(1, result.get("reportedCalls").asInt());
        assertEquals(1, result.get("unknownCalls").asInt());
        assertEquals(100, result.get("inputTokens").asInt());
        assertEquals(10, result.get("outputTokens").asInt());
        assertEquals(20, result.get("cacheReadTokens").asInt());
        assertEquals(0, result.get("cacheWriteTokens").asInt());
        assertMoney("0.00012200", result.get("reportedCost"));
        assertMoney("0.00260000", result.get("estimatedCost"));
        assertEquals(5, result.get("byPurpose").size());
        assertEquals(0, result.at("/byPurpose/DAILY_SUMMARY/calls").asInt());
        assertEquals(result, read(base(group) + "/usage?date=2026-11-12"));
        assertEquals(0, read(base(group) + "/usage?date=2026-11-11").get("calls").asInt());
        for (String date : List.of("wrong", "2026-02-30", "2026-1-1")) {
            mvc.perform(get(base(group) + "/usage?date=" + date))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isString());
        }
    }

    @Test
    void A06NoCallsUnknownMissingPriceAndMixedCurrenciesRemainDistinct() throws Exception {
        UUID empty = group();
        var zero = read(base(empty) + "/usage");
        assertEquals(0, zero.get("calls").asInt());
        assertMoney("0", zero.get("reportedCost"));
        assertMoney("0", zero.get("estimatedCost"));
        UUID group = group();
        var request = request(group, Purpose.CHAT);
        calls.begin(request);
        calls.dispatched(request.callId());
        calls.finish(request.callId(), "FAILED", "TIMEOUT");
        assertTrue(read(base(group) + "/usage").get("reportedCost").isNull());
        calls.usage(request.callId(), new ModelUsage(100L, 10L, 20L, 0L, 5L, UsageStatus.REPORTED));
        db.exec("update model_call set reported_cost=null where id=?", request.callId());
        assertTrue(read(base(group) + "/usage").get("reportedCost").isNull());
        var second = request(group, Purpose.MEMORY_QUERY);
        calls.begin(second);
        calls.dispatched(second.callId());
        calls.usage(
                second.callId(), new ModelUsage(100L, 10L, 20L, 0L, null, UsageStatus.REPORTED));
        calls.finish(second.callId(), "SUCCEEDED", null);
        db.exec("update model_call set reported_cost='0.00012200' where id=?", request.callId());
        db.exec("update model_call set price_version='test-v2' where id=?", second.callId());
        var mixed = read(base(group) + "/usage");
        assertEquals("mixed", mixed.get("priceVersion").asText());
        assertMoney("0.00024400", mixed.get("reportedCost"));
        db.exec("update model_call set currency='CNY' where id=?", second.callId());
        mixed = read(base(group) + "/usage");
        assertEquals("mixed", mixed.get("currency").asText());
        assertTrue(mixed.get("reportedCost").isNull());
        assertTrue(mixed.get("estimatedCost").isNull());
    }

    private UUID group() {
        UUID id = UUID.randomUUID();
        db.exec(
                "insert into group_config(id,channel,account_id,external_id,name,enabled) values(?,'QQ','test',?,'合成管理群',true)",
                id,
                id.toString());
        return id;
    }

    private String base(UUID group) {
        return "/api/admin/groups/" + group + "/memory";
    }

    private LinkedHashMap<String, Object> settings(long revision) {
        return new LinkedHashMap<>(
                Map.of(
                        "revision",
                        revision,
                        "mode",
                        "RAW",
                        "strategyVersion",
                        1,
                        "summaryTriggerTokens",
                        6000,
                        "summaryCooldownMinutes",
                        30));
    }

    private JsonNode read(String path) throws Exception {
        return json.readTree(
                mvc.perform(get(path))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private JsonNode save(UUID group, Map<String, Object> input) throws Exception {
        return json.readTree(
                mvc.perform(
                                put(base(group) + "/settings")
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(json.writeValueAsString(input)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private void reject(UUID group, Map<String, Object> input, String before) throws Exception {
        mvc.perform(
                        put(base(group) + "/settings")
                                .with(csrf())
                                .contentType("application/json")
                                .content(json.writeValueAsString(input)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString());
        assertEquals(before, read(base(group) + "/settings").toString());
    }

    private GenerationRequest request(UUID group, Purpose purpose) {
        return new GenerationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                group,
                0,
                purpose,
                "s",
                "q".repeat(743),
                8000,
                800,
                30);
    }

    private void assertMoney(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()));
    }

    private UUID run(UUID group, Map<String, Object> stats, Instant created, long epoch) {
        UUID conversation = UUID.randomUUID(), message = UUID.randomUUID(), run = UUID.randomUUID();
        db.exec(
                "insert into conversation(id,group_id,sender_id) values(?,?,?)",
                conversation,
                group,
                conversation.toString());
        db.exec(
                "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,epoch,kind) values(?,?,?,'user','合成','prompt',?,?,'TEXT')",
                message,
                group,
                message.toString(),
                created,
                epoch);
        var saved = stats == null ? null : new LinkedHashMap<>(stats);
        if (saved != null) {
            saved.put("runId", run);
        }
        db.exec(
                "insert into agent_run(id,group_id,message_id,conversation_id,generation,epoch,kind,prompt,state,context_stats,created_at) values(?,?,?,?,0,?,'CHAT','prompt','RUNNING',?,?)",
                run,
                group,
                message,
                conversation,
                epoch,
                saved == null ? null : db.json(saved),
                created);
        return run;
    }
}
