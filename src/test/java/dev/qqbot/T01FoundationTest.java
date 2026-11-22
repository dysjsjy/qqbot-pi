package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import dev.qqbot.memory.*;
import dev.qqbot.model.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;

class T01FoundationTest {
    @TempDir Path directory;
    private final List<MemoryV2TestDatabase> databases = new ArrayList<>();

    MemoryV2TestDatabase createDatabase(Path path, String target) {
        return new MemoryV2TestDatabase(path, target);
    }

    private MemoryV2TestDatabase database(String name, String target) {
        var fixture = createDatabase(directory.resolve(name + ".db"), target);
        databases.add(fixture);
        return fixture;
    }

    @AfterEach
    void closeDatabases() {
        databases.forEach(MemoryV2TestDatabase::close);
    }

    @Test
    void F01EmptyDatabaseHasNewTablesConstraintsAndValidMigrations() throws Exception {
        var fixture = database("empty", null);
        var tables =
                fixture.db.list("select name from sqlite_master where type='table'").stream()
                        .map(row -> row.get("name"))
                        .toList();
        assertTrue(
                tables.containsAll(
                        List.of(
                                "model_call",
                                "memory_search_chunk",
                                "memory_search_source",
                                "memory_search_term",
                                "memory_search_state")));
        Flyway.configure()
                .dataSource(fixture.source)
                .locations("classpath:db/sqlite")
                .load()
                .validate();
        UUID groupId = fixture.group("one");
        fixture.message(groupId, "first", "user", "body", 1, Instant.now());
        var duplicate =
                assertThrows(
                        DataAccessException.class,
                        () -> fixture.message(groupId, "second", "user", "body", 1, Instant.now()));
        assertTrue(
                duplicate.getMostSpecificCause().getMessage().contains("SQLITE_CONSTRAINT_UNIQUE"));
        assertEquals(
                1,
                fixture.db.list("select id from group_message where group_id=?", groupId).size());
        UUID otherGroup = fixture.group("two");
        fixture.message(otherGroup, "same-sequence", "user", "other group", 1, Instant.EPOCH);
        var expectedColumns =
                new LinkedHashMap<>(
                        Map.of(
                                "group_config", List.of("event_seq"),
                                "group_message", List.of("event_seq", "reply_to_key"),
                                "agent_run",
                                        List.of(
                                                "context_seq",
                                                "context_occurred_at",
                                                "context_stats"),
                                "outbox", List.of("event_seq", "delivered_at"),
                                "memory_policy",
                                        List.of(
                                                "revision",
                                                "strategy_version",
                                                "summary_trigger_tokens",
                                                "summary_cooldown_minutes"),
                                "memory_document", List.of("strategy_version"),
                                "model_call",
                                        List.of(
                                                "id",
                                                "run_id",
                                                "group_id",
                                                "epoch",
                                                "purpose",
                                                "provider",
                                                "model",
                                                "state",
                                                "dispatched",
                                                "usage_status",
                                                "estimated_input_tokens",
                                                "max_output_tokens",
                                                "input_tokens",
                                                "output_tokens",
                                                "cache_read_tokens",
                                                "cache_write_tokens",
                                                "reasoning_tokens",
                                                "reported_cost",
                                                "estimated_cost",
                                                "currency",
                                                "price_version",
                                                "price_snapshot",
                                                "error_code",
                                                "created_at",
                                                "finished_at"),
                                "memory_search_chunk",
                                        List.of(
                                                "id",
                                                "group_id",
                                                "epoch",
                                                "source_kind",
                                                "source_key",
                                                "part_index",
                                                "body",
                                                "content_hash",
                                                "source_time",
                                                "indexed_at",
                                                "document_id"),
                                "memory_search_source", List.of("chunk_id", "message_id"),
                                "memory_search_term", List.of("chunk_id", "group_id", "term")));
        expectedColumns.put(
                "memory_search_state",
                List.of("group_id", "epoch", "last_message_seq", "manual_hash", "updated_at"));
        try (var connection = fixture.source.getConnection()) {
            for (var table : expectedColumns.entrySet()) {
                var actualColumns = new HashSet<String>();
                try (var columns =
                        connection.getMetaData().getColumns(null, null, table.getKey(), null)) {
                    while (columns.next()) {
                        actualColumns.add(
                                columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                    }
                }
                assertTrue(
                        actualColumns.containsAll(table.getValue()),
                        table.getKey() + ": " + actualColumns);
            }
        }
        insertOutbox(fixture, groupId, 8L);
        assertUnique(fixture, () -> insertOutbox(fixture, groupId, 8L));
        insertOutbox(fixture, otherGroup, 8L);
        insertOutbox(fixture, groupId, null);
        insertOutbox(fixture, groupId, null);
        assertEquals(4, fixture.db.list("select id from outbox").size());
        UUID callId = UUID.randomUUID(), runId = UUID.randomUUID();
        insertCall(fixture, callId, runId, groupId);
        assertUnique(fixture, () -> insertCall(fixture, callId, runId, groupId));
        insertCall(fixture, UUID.randomUUID(), runId, groupId);
        assertEquals(2, fixture.db.list("select id from model_call where run_id=?", runId).size());
        UUID manual = insertChunk(fixture, groupId, 2, "MANUAL", "manual", 0, null);
        assertUnique(fixture, () -> insertChunk(fixture, groupId, 2, "MANUAL", "manual", 0, null));
        insertChunk(fixture, otherGroup, 2, "MANUAL", "manual", 0, null);
        insertChunk(fixture, groupId, 3, "MANUAL", "manual", 0, null);
        UUID raw = insertChunk(fixture, groupId, 2, "RAW", "manual", 0, null);
        UUID source = fixture.message(groupId, "raw-source", "user", "source", 2, Instant.EPOCH);
        fixture.db.exec(
                "insert into memory_search_source(chunk_id,message_id) values(?,?)", raw, source);
        insertChunk(fixture, groupId, 2, "MANUAL", "different", 0, null);
        insertChunk(fixture, groupId, 2, "MANUAL", "manual", 1, null);
        assertEquals(6, fixture.db.list("select id from memory_search_chunk").size());
        UUID document = UUID.randomUUID(),
                secondSource =
                        fixture.message(
                                groupId, "summary-source", "user", "source", 3, Instant.EPOCH);
        fixture.db.exec(
                "insert into memory_document(id,group_id,epoch,kind,state,source_count) values(?,?,2,'SUMMARY','READY',2)",
                document,
                groupId);
        UUID summary = insertChunk(fixture, groupId, 2, "SUMMARY", "summary", 0, document);
        fixture.db.exec(
                "insert into memory_search_source(chunk_id,message_id) values(?,?)",
                summary,
                source);
        assertUnique(
                fixture,
                () ->
                        fixture.db.exec(
                                "insert into memory_search_source(chunk_id,message_id) values(?,?)",
                                summary,
                                source));
        fixture.db.exec(
                "insert into memory_search_source(chunk_id,message_id) values(?,?)",
                summary,
                secondSource);
        fixture.db.exec(
                "insert into memory_search_term(chunk_id,group_id,term) values(?,?,'first')",
                manual,
                groupId);
        assertUnique(
                fixture,
                () ->
                        fixture.db.exec(
                                "insert into memory_search_term(chunk_id,group_id,term) values(?,?,'first')",
                                manual,
                                groupId));
        fixture.db.exec(
                "insert into memory_search_term(chunk_id,group_id,term) values(?,?,'second')",
                manual,
                groupId);
        fixture.db.exec("insert into memory_search_state(group_id,epoch) values(?,2)", groupId);
        assertUnique(
                fixture,
                () ->
                        fixture.db.exec(
                                "insert into memory_search_state(group_id,epoch) values(?,2)",
                                groupId));
        fixture.db.exec("insert into memory_search_state(group_id,epoch) values(?,2)", otherGroup);
        assertEquals(
                2,
                fixture.db
                        .list(
                                "select message_id from memory_search_source where chunk_id=?",
                                summary)
                        .size());
        assertEquals(
                2,
                fixture.db
                        .list("select term from memory_search_term where chunk_id=?", manual)
                        .size());
        assertEquals(2, fixture.db.list("select group_id from memory_search_state").size());
        var foreignKey =
                assertThrows(
                        DataAccessException.class,
                        () ->
                                fixture.db.exec(
                                        "insert into memory_search_state(group_id,epoch) values(?,0)",
                                        UUID.randomUUID()));
        assertTrue(
                foreignKey
                        .getMostSpecificCause()
                        .getMessage()
                        .contains("SQLITE_CONSTRAINT_FOREIGNKEY"));
    }

    @Test
    void F02UpgradeBackfillsPerGroupInReceivedThenIdOrderWithoutChangingContent() {
        var fixture = database("upgrade", "6");
        var original = new LinkedHashMap<UUID, List<UUID>>();
        for (String groupName : List.of("first", "second")) {
            UUID groupId = fixture.group(groupName);
            List<UUID> ids =
                    List.of(
                            new UUID(0, 3),
                            new UUID(groupName.equals("first") ? 1 : 2, 2),
                            new UUID(groupName.equals("first") ? 1 : 2, 1));
            if (original.size() == 1) {
                ids = List.of(new UUID(3, 3), ids.get(1), ids.get(2));
            }
            for (int index = 0; index < ids.size(); index++) {
                fixture.db.exec(
                        "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,received_at,epoch,kind) values(?,?,?,'u','n','untouched',?,?,2,'TEXT')",
                        ids.get(index),
                        groupId,
                        "key" + index,
                        Instant.ofEpochMilli(50),
                        Instant.ofEpochMilli(index == 0 ? 100 : 200));
            }
            original.put(groupId, List.of(ids.get(0), ids.get(2), ids.get(1)));
        }
        migrate(fixture);
        original.forEach(
                (groupId, expected) -> {
                    var rows =
                            fixture.db.list(
                                    "select id,event_seq,body,epoch from group_message where group_id=? order by event_seq",
                                    groupId);
                    assertEquals(expected, rows.stream().map(row -> row.get("id")).toList());
                    assertEquals(
                            List.of(1L, 2L, 3L),
                            rows.stream().map(row -> row.get("event_seq")).toList());
                    assertTrue(
                            rows.stream()
                                    .allMatch(
                                            row ->
                                                    row.get("body").equals("untouched")
                                                            && row.get("epoch").equals(2L)));
                    assertEquals(
                            3L,
                            fixture.db
                                    .one("select event_seq from group_config where id=?", groupId)
                                    .get("event_seq"));
                });
    }

    @Test
    void F03UpgradeDoesNotInventDeliveryTimesAndCancelsOrphanQueuedRuns() {
        var fixture = database("outbox", "6");
        UUID groupId = fixture.group("one"), conversationId = UUID.randomUUID();
        fixture.db.exec(
                "insert into conversation(id,group_id,sender_id) values(?,?,'u')",
                conversationId,
                groupId);
        for (String state : List.of("SENT", "UNKNOWN", "SENDING")) {
            fixture.db.exec(
                    "insert into outbox(id,group_id,result_key,epoch,part_index,body,state) values(?,?,?,2,0,'original',?)",
                    UUID.randomUUID(),
                    groupId,
                    state,
                    state);
        }
        UUID orphan = UUID.randomUUID();
        fixture.db.exec(
                "insert into agent_run(id,group_id,conversation_id,generation,epoch,kind,prompt) values(?,?,?,0,2,'CHAT','orphan')",
                orphan,
                groupId,
                conversationId);
        var validRuns = new LinkedHashMap<UUID, UUID>();
        for (int groupIndex = 0; groupIndex < 2; groupIndex++) {
            UUID scope = groupIndex == 0 ? groupId : fixture.group("two");
            UUID conversation = UUID.randomUUID();
            fixture.db.exec(
                    "insert into conversation(id,group_id,sender_id) values(?,?,'valid')",
                    conversation,
                    scope);
            for (int number = 1; number <= 3; number++) {
                legacyMessage(
                        fixture,
                        new UUID(0, groupIndex * 10L + number),
                        scope,
                        "m" + number,
                        Instant.parse("2026-11-11T03:00:00Z").plusSeconds(number * 60L));
            }
            UUID validRun = UUID.randomUUID(), origin = new UUID(0, groupIndex * 10L + 2);
            fixture.db.exec(
                    "insert into agent_run(id,group_id,message_id,conversation_id,generation,epoch,kind,prompt) values(?,?,?,?,0,2,'CHAT','valid')",
                    validRun,
                    scope,
                    origin,
                    conversation);
            validRuns.put(validRun, origin);
        }
        migrate(fixture);
        for (var row : fixture.db.list("select * from outbox")) {
            assertNull(row.get("event_seq"));
            assertNull(row.get("delivered_at"));
            assertEquals("original", row.get("body"));
        }
        assertEquals(
                "CANCELLED",
                fixture.db.one("select state from agent_run where id=?", orphan).get("state"));
        assertEquals(
                "SOURCE_MISSING",
                fixture.db
                        .one("select error_code from agent_run where id=?", orphan)
                        .get("error_code"));
        validRuns.forEach(
                (run, origin) -> {
                    var snapshot =
                            fixture.db.one(
                                    "select state,message_id,context_seq,context_occurred_at from agent_run where id=?",
                                    run);
                    assertEquals("QUEUED", snapshot.get("state"));
                    assertEquals(origin, snapshot.get("message_id"));
                    assertEquals(2L, snapshot.get("context_seq"));
                    assertEquals(
                            Timestamp.from(Instant.parse("2026-11-11T03:02:00Z")),
                            snapshot.get("context_occurred_at"));
                });
    }

    @Test
    void F04ExistingPoliciesAndDocumentsStayOnVersionOne() throws Exception {
        var fixture = database("policies", "6");
        var fileHashes = new LinkedHashMap<Path, String>();
        for (String mode : List.of("RAW", "SUMMARY")) {
            UUID groupId = fixture.group(mode);
            fixture.db.exec("insert into memory_policy(group_id,mode) values(?,?)", groupId, mode);
            for (String state : List.of("READY", "FAILED")) {
                UUID document = UUID.randomUUID();
                int sourceCount = mode.equals("SUMMARY") ? 2 : 1;
                fixture.db.exec(
                        "insert into memory_document(id,group_id,epoch,kind,state,source_count,error_code) values(?,?,2,?,?,?,?)",
                        document,
                        groupId,
                        mode,
                        state,
                        sourceCount,
                        state.equals("FAILED") ? "OLD_FAILURE" : null);
                for (int index = 0; index < sourceCount; index++) {
                    UUID message = UUID.randomUUID();
                    legacyMessage(
                            fixture,
                            message,
                            groupId,
                            state + "-" + index,
                            Instant.parse("2026-11-11T03:02:03.123Z"));
                    fixture.db.exec(
                            "insert into memory_document_source(document_id,message_id,kind) values(?,?,?)",
                            document,
                            message,
                            mode);
                }
                Path file = directory.resolve(mode + "-" + state + ".md");
                Files.writeString(file, "合成旧文档 " + document, StandardCharsets.UTF_8);
                fileHashes.put(
                        file,
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(Files.readAllBytes(file))));
            }
        }
        var policies = fixture.db.list("select group_id,mode from memory_policy order by group_id");
        var documents =
                fixture.db.list(
                        "select id,group_id,epoch,kind,state,source_count,attempt,error_code,created_at,finished_at from memory_document order by id");
        var sources =
                fixture.db.list(
                        "select document_id,message_id,kind from memory_document_source order by document_id,message_id");
        migrate(fixture);
        assertEquals(
                policies,
                fixture.db.list("select group_id,mode from memory_policy order by group_id"));
        assertEquals(
                documents,
                fixture.db.list(
                        "select id,group_id,epoch,kind,state,source_count,attempt,error_code,created_at,finished_at from memory_document order by id"));
        assertEquals(
                sources,
                fixture.db.list(
                        "select document_id,message_id,kind from memory_document_source order by document_id,message_id"));
        assertEquals(6, sources.size());
        for (var file : fileHashes.entrySet()) {
            byte[] contents = Files.readAllBytes(file.getKey());
            String actualHash =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
            assertEquals(file.getValue(), actualHash);
        }
        for (var row : fixture.db.list("select * from memory_policy")) {
            assertEquals(1, ((Number) row.get("strategy_version")).intValue());
            assertFalse(row.containsKey("context_mode"));
        }
        assertEquals(
                4,
                fixture.db.list("select id from memory_document where strategy_version=1").size());
        assertTrue(fixture.db.list("select * from model_call").isEmpty());
    }

    @Test
    void contextModeMigrationPreservesNonDefaultArchiveSettings() {
        var fixture = database("single-context", "11");
        for (String previous : List.of("LEGACY", "BUDGETED")) {
            UUID group = fixture.group(previous);
            fixture.db.exec(
                    "insert into memory_policy(group_id,mode,revision,context_mode,strategy_version,summary_trigger_tokens,summary_cooldown_minutes) values(?,'SUMMARY',7,?,2,7100,45)",
                    group,
                    previous);
        }
        String settings =
                "select group_id,mode,revision,strategy_version,summary_trigger_tokens,summary_cooldown_minutes from memory_policy order by group_id";
        var before = fixture.db.list(settings);
        migrate(fixture);
        assertEquals(before, fixture.db.list(settings));
        assertTrue(
                fixture.db.list("pragma table_info(memory_policy)").stream()
                        .noneMatch(column -> "context_mode".equals(column.get("name"))));
    }

    @Test
    void F05NewDatabaseValuesHaveStableJavaTypesAndKeepNulls() {
        var fixture = database("types", null);
        UUID groupId = fixture.group("one"), callId = UUID.randomUUID(), runId = UUID.randomUUID();
        Instant time = Instant.parse("2026-11-11T03:02:03.123Z");
        fixture.db.exec(
                "insert into model_call(id,run_id,group_id,epoch,purpose,provider,model,dispatched,estimated_input_tokens,max_output_tokens,currency,price_version,price_snapshot,finished_at) values(?,?,?,2,'CHAT','p','m',true,2147483648,800,'USD','v1','{}',?)",
                callId,
                runId,
                groupId,
                time);
        var row = fixture.db.one("select * from model_call where id=?", callId);
        assertEquals(runId, row.get("run_id"));
        assertEquals(true, row.get("dispatched"));
        assertEquals(2147483648L, row.get("estimated_input_tokens"));
        assertEquals(Timestamp.from(time), row.get("finished_at"));
        assertNull(row.get("input_tokens"));
        UUID unknown = UUID.randomUUID();
        fixture.db.exec(
                "insert into model_call(id,run_id,group_id,epoch,purpose,provider,model,dispatched,estimated_input_tokens,max_output_tokens,currency,price_version,price_snapshot) values(?,?,null,2,'CHAT','p','m',false,0,800,'USD','v1','{}')",
                unknown,
                runId);
        var nulls = fixture.db.one("select * from model_call where id=?", unknown);
        assertEquals(unknown, nulls.get("id"));
        assertEquals(false, nulls.get("dispatched"));
        assertEquals(0L, nulls.get("estimated_input_tokens"));
        assertNull(nulls.get("group_id"));
        assertNull(nulls.get("finished_at"));
        assertNull(nulls.get("input_tokens"));
        assertNull(fixture.db.one("select null as dispatched").get("dispatched"));
    }

    @Test
    void F06BudgetAndImmutableTypesValidateInputs() {
        assertThrows(IllegalArgumentException.class, () -> settings(10023, null));
        assertEquals(10024, settings(10024, null).contextWindow());
        assertThrows(
                IllegalArgumentException.class, () -> settings(32768, new BigDecimal("-0.01")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelUsage(-1L, null, null, null, null, UsageStatus.REPORTED));
        var ids = new ArrayList<UUID>();
        var evidence = new Evidence("key", SourceKind.MANUAL, "body", ids, Instant.EPOCH, 1);
        ids.add(UUID.randomUUID());
        assertTrue(evidence.messageIds().isEmpty());
        var layers = new LinkedHashMap<String, Integer>();
        layers.put("system", 1);
        var stats = new ContextStats(1, layers, List.of(), List.of(), List.of(), "hash");
        layers.put("system", 2);
        assertEquals(1, stats.layerTokens().get("system"));
        assertThrows(
                UnsupportedOperationException.class, () -> stats.layerTokens().put("history", 1));
        assertNull(
                new HistoryEvent(
                                "message:key:0",
                                1,
                                Instant.EPOCH,
                                Role.USER,
                                "u",
                                "n",
                                "text",
                                null,
                                null,
                                null,
                                false)
                        .conversationId());
        assertNull(ModelUsage.unknown().inputTokens());
        assertEquals(13, TokenEstimator.estimateText("中文🙂abc"));
        assertEquals(269, TokenEstimator.estimateInput("中文🙂abc", ""));
    }

    private MemoryProperties settings(int window, BigDecimal inputPrice) {
        return new MemoryProperties(
                8000, 800, window, 1024, 1000, "USD", inputPrice, null, null, null, "test");
    }

    private void assertUnique(MemoryV2TestDatabase fixture, Runnable action) {
        var failure = assertThrows(DataAccessException.class, action::run);
        assertTrue(
                failure.getMostSpecificCause().getMessage().contains("SQLITE_CONSTRAINT_UNIQUE")
                        || failure.getMostSpecificCause()
                                .getMessage()
                                .contains("SQLITE_CONSTRAINT_PRIMARYKEY"));
    }

    private void insertOutbox(MemoryV2TestDatabase fixture, UUID group, Long sequence) {
        UUID id = UUID.randomUUID();
        fixture.db.exec(
                "insert into outbox(id,group_id,result_key,epoch,part_index,body,event_seq) values(?,?,?,2,0,'synthetic',?)",
                id,
                group,
                id.toString(),
                sequence);
    }

    private void insertCall(MemoryV2TestDatabase fixture, UUID call, UUID run, UUID group) {
        fixture.db.exec(
                "insert into model_call(id,run_id,group_id,epoch,purpose,provider,model,estimated_input_tokens,max_output_tokens,currency,price_version,price_snapshot) values(?,?,?,2,'CHAT','p','m',0,800,'USD','fixture','{}')",
                call,
                run,
                group);
    }

    private UUID insertChunk(
            MemoryV2TestDatabase fixture,
            UUID group,
            long epoch,
            String kind,
            String key,
            int part,
            UUID document) {
        UUID id = UUID.randomUUID();
        fixture.db.exec(
                "insert into memory_search_chunk(id,group_id,epoch,source_kind,source_key,part_index,body,content_hash,source_time,document_id) values(?,?,?,?,?,?,'synthetic','hash',?,?)",
                id,
                group,
                epoch,
                kind,
                key,
                part,
                Instant.EPOCH,
                document);
        return id;
    }

    private void legacyMessage(
            MemoryV2TestDatabase fixture, UUID id, UUID group, String key, Instant occurred) {
        fixture.db.exec(
                "insert into group_message(id,group_id,message_key,sender_id,sender_name,body,occurred_at,received_at,epoch,kind) values(?,?,?,'u','name','untouched',?,?,2,'TEXT')",
                id,
                group,
                key,
                occurred,
                Instant.parse("2026-11-11T03:05:00Z"));
    }

    private void migrate(MemoryV2TestDatabase fixture) {
        Flyway.configure()
                .dataSource(fixture.source)
                .locations("classpath:db/sqlite")
                .load()
                .migrate();
    }
}
