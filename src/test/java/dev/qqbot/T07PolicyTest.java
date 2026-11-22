package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import dev.qqbot.memory.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class T07PolicyTest {
    @TempDir Path directory;
    private MemoryV2TestDatabase fixture;
    private UUID groupId;
    private MemoryPolicyService policies;

    @BeforeEach
    void prepare() {
        fixture = database();
        groupId = fixture.group("policy");
        policies = new MemoryPolicyService(fixture.db);
    }

    MemoryV2TestDatabase database() {
        return new MemoryV2TestDatabase(directory.resolve("policy.db"));
    }

    @AfterEach
    void cleanup() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void defaultsDoNotCreateOrUpgradeExistingPolicies() {
        var expected =
                Map.of(
                        "revision",
                        0L,
                        "mode",
                        "RAW",
                        "strategyVersion",
                        1,
                        "summaryTriggerTokens",
                        6000,
                        "summaryCooldownMinutes",
                        30);
        assertEquals(expected, policies.settings(groupId));
        assertTrue(fixture.db.list("select * from memory_policy").isEmpty());
        fixture.db.exec("insert into memory_policy(group_id,mode) values(?,'SUMMARY')", groupId);
        var existing = policies.settings(groupId);
        assertEquals("SUMMARY", existing.get("mode"));
        assertEquals(1, existing.get("strategyVersion"));
        assertEquals(0L, existing.get("revision"));
    }

    @Test
    void updateRequiresRevisionAndSavesAllSettingsTogether() {
        var saved = policies.updateSettings(groupId, 0, "SUMMARY", 2, 7000, 60);
        assertEquals(
                Map.of(
                        "revision",
                        1L,
                        "mode",
                        "SUMMARY",
                        "strategyVersion",
                        2,
                        "summaryTriggerTokens",
                        7000,
                        "summaryCooldownMinutes",
                        60),
                saved);
        assertEquals(saved, policies.settings(groupId));
        assertEquals(
                409,
                assertThrows(
                                ResponseStatusException.class,
                                () -> policies.updateSettings(groupId, 0, "RAW", 1, 6000, 30))
                        .getStatusCode()
                        .value());
        assertEquals(saved, policies.settings(groupId));
    }

    @Test
    void invalidSettingsDoNotPartiallyUpdateOrAdvanceRevision() {
        var saved = policies.updateSettings(groupId, 0, "SUMMARY", 2, 7000, 60);
        for (Runnable invalid :
                List.<Runnable>of(
                        () -> policies.updateSettings(groupId, 1, null, 2, 7000, 60),
                        () -> policies.updateSettings(groupId, 1, "UNKNOWN", 2, 7000, 60),
                        () -> policies.updateSettings(groupId, 1, "RAW", 3, 7000, 60),
                        () -> policies.updateSettings(groupId, 1, "RAW", 2, 1023, 60),
                        () -> policies.updateSettings(groupId, 1, "RAW", 2, 16001, 60),
                        () -> policies.updateSettings(groupId, 1, "RAW", 2, 7000, 4),
                        () -> policies.updateSettings(groupId, 1, "RAW", 2, 7000, 1441))) {
            assertEquals(
                    400,
                    assertThrows(ResponseStatusException.class, invalid::run)
                            .getStatusCode()
                            .value());
            assertEquals(saved, policies.settings(groupId));
        }
    }

    @Test
    void legacyModeChangePreservesNewFieldsAndInvalidatesOldForm() {
        policies.updateSettings(groupId, 0, "SUMMARY", 2, 7000, 60);
        var saved = policies.configureLegacy(groupId, "RAW");
        assertEquals(
                Map.of(
                        "revision",
                        2L,
                        "mode",
                        "RAW",
                        "strategyVersion",
                        2,
                        "summaryTriggerTokens",
                        7000,
                        "summaryCooldownMinutes",
                        60),
                saved);
        assertEquals(
                409,
                assertThrows(
                                ResponseStatusException.class,
                                () -> policies.updateSettings(groupId, 1, "RAW", 1, 6000, 30))
                        .getStatusCode()
                        .value());
        UUID another = fixture.group("legacy");
        var created = policies.configureLegacy(another, "SUMMARY");
        assertEquals(1L, created.get("revision"));
        assertEquals(1, created.get("strategyVersion"));
    }

    @Test
    void concurrentFirstSaveHasExactlyOneWinner() throws Exception {
        var gate = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> saveAfter(gate, "RAW"));
            var second = executor.submit(() -> saveAfter(gate, "SUMMARY"));
            gate.countDown();
            var codes =
                    new ArrayList<>(
                            List.of(
                                    first.get(10, TimeUnit.SECONDS),
                                    second.get(10, TimeUnit.SECONDS)));
            Collections.sort(codes);
            assertEquals(List.of(200, 409), codes);
            assertEquals(1L, policies.settings(groupId).get("revision"));
            assertEquals(1, fixture.db.list("select * from memory_policy").size());
        } finally {
            gate.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void unknownGroupIsNotFoundForReadAndBothWritePaths() {
        UUID unknown = UUID.randomUUID();
        for (Runnable query :
                List.<Runnable>of(
                        () -> policies.settings(unknown),
                        () -> policies.configureLegacy(unknown, "RAW"),
                        () -> policies.updateSettings(unknown, 0, "RAW", 1, 6000, 30))) {
            assertEquals(
                    404,
                    assertThrows(ResponseStatusException.class, query::run)
                            .getStatusCode()
                            .value());
        }
        assertTrue(fixture.db.list("select * from memory_policy").isEmpty());
    }

    @Test
    void concurrentExistingRevisionHasOneWholeWinnerWithoutMixedFields() throws Exception {
        policies.updateSettings(groupId, 0, "SUMMARY", 2, 7000, 60);
        var ready = new CountDownLatch(2);
        var gate = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        Callable<Map<String, Object>> firstChange =
                () -> {
                    ready.countDown();
                    assertTrue(gate.await(5, TimeUnit.SECONDS));
                    try {
                        return policies.updateSettings(groupId, 1, "RAW", 1, 8000, 90);
                    } catch (ResponseStatusException conflict) {
                        assertEquals(409, conflict.getStatusCode().value());
                        return null;
                    }
                };
        Callable<Map<String, Object>> secondChange =
                () -> {
                    ready.countDown();
                    assertTrue(gate.await(5, TimeUnit.SECONDS));
                    try {
                        return policies.updateSettings(groupId, 1, "SUMMARY", 2, 9000, 120);
                    } catch (ResponseStatusException conflict) {
                        assertEquals(409, conflict.getStatusCode().value());
                        return null;
                    }
                };
        try {
            var first = executor.submit(firstChange);
            var second = executor.submit(secondChange);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            gate.countDown();
            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);
            assertTrue((firstResult == null) != (secondResult == null));
            var saved = policies.settings(groupId);
            assertEquals(firstResult == null ? secondResult : firstResult, saved);
            var firstExpected =
                    Map.<String, Object>of(
                            "revision",
                            2L,
                            "mode",
                            "RAW",
                            "strategyVersion",
                            1,
                            "summaryTriggerTokens",
                            8000,
                            "summaryCooldownMinutes",
                            90);
            var secondExpected =
                    Map.<String, Object>of(
                            "revision",
                            2L,
                            "mode",
                            "SUMMARY",
                            "strategyVersion",
                            2,
                            "summaryTriggerTokens",
                            9000,
                            "summaryCooldownMinutes",
                            120);
            assertTrue(saved.equals(firstExpected) || saved.equals(secondExpected));
            assertEquals(
                    1, fixture.db.list("select id from group_config where id=?", groupId).size());
            assertEquals(
                    1,
                    fixture.db
                            .list("select group_id from memory_policy where group_id=?", groupId)
                            .size());
        } finally {
            gate.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private int saveAfter(CountDownLatch gate, String mode) throws Exception {
        assertTrue(gate.await(5, TimeUnit.SECONDS));
        try {
            policies.updateSettings(groupId, 0, mode, 2, 6000, 30);
            return 200;
        } catch (ResponseStatusException conflict) {
            return conflict.getStatusCode().value();
        }
    }
}
