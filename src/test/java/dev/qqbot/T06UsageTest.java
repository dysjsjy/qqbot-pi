package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.qqbot.model.*;
import dev.qqbot.usage.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class T06UsageTest {
    @TempDir Path directory;
    private static final Instant NOW = Instant.parse("2026-11-11T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ModelUsage REPORTED =
            new ModelUsage(100L, 10L, 20L, 0L, 5L, UsageStatus.REPORTED);
    private MemoryV2TestDatabase fixture;
    private UUID groupId;
    private MemoryProperties prices;

    @BeforeEach
    void prepare() {
        fixture = database();
        groupId = fixture.group("one");
        prices = settings("USD", "v1", new BigDecimal("1.25"));
    }

    MemoryV2TestDatabase database() {
        return new MemoryV2TestDatabase(directory.resolve("usage.db"));
    }

    @AfterEach
    void closeDatabase() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void U01SameBusinessRunHasIndependentAttemptRowsIncludingFailure() {
        var delegate = mock(TextGenerator.class);
        var failure = new IllegalStateException("PI_DEADLINE");
        when(delegate.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenAnswer(
                        invocation -> {
                            GenerationRequest request = invocation.getArgument(0);
                            GenerationObserver observer = invocation.getArgument(1);
                            observer.dispatched();
                            if (request.purpose() == Purpose.MEMORY_QUERY) {
                                throw failure;
                            }
                            observer.usage(REPORTED);
                            return new TextGenerator.Result("回答", null, REPORTED);
                        });
        var metered = new MeteredTextGenerator(delegate, store(prices, CLOCK));
        UUID runId = UUID.randomUUID();
        for (Purpose purpose :
                List.of(Purpose.CHAT, Purpose.MEMORY_QUERY, Purpose.MEMORY_SUMMARY)) {
            var request = request(runId, purpose);
            if (purpose == Purpose.MEMORY_QUERY) {
                assertSame(
                        failure,
                        assertThrows(
                                IllegalStateException.class,
                                () -> metered.generate(request, GenerationObserver.noop())));
            } else {
                metered.generate(request, GenerationObserver.noop());
            }
        }
        var rows = fixture.db.list("select * from model_call where run_id=?", runId);
        assertEquals(3, rows.size());
        assertEquals(1, rows.stream().filter(row -> row.get("state").equals("FAILED")).count());
        var usage = queries(prices).daily(groupId, LocalDate.of(2026, 11, 11));
        assertEquals(3L, usage.totals().calls());
        assertEquals(3L, usage.totals().dispatchedCalls());
        assertEquals(1L, usage.totals().unknownCalls());
        assertEquals(1L, purpose(usage, Purpose.MEMORY_QUERY).calls());
    }

    @Test
    void U02RepeatedCumulativeUsageAndReturnedUsageAreNotAdded() {
        var delegate = mock(TextGenerator.class);
        when(delegate.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenAnswer(
                        invocation -> {
                            GenerationObserver observer = invocation.getArgument(1);
                            observer.dispatched();
                            observer.usage(REPORTED);
                            observer.usage(REPORTED);
                            return new TextGenerator.Result("回答", null, REPORTED);
                        });
        var request = request(UUID.randomUUID(), Purpose.CHAT);
        new MeteredTextGenerator(delegate, store(prices, CLOCK))
                .generate(request, GenerationObserver.noop());
        var row = fixture.db.one("select * from model_call where id=?", request.callId());
        assertEquals(100L, row.get("input_tokens"));
        assertEquals(10L, row.get("output_tokens"));
        assertEquals(new BigDecimal("0.00012200"), amount(row.get("reported_cost")));
        assertNull(row.get("estimated_cost"));
        assertFalse(row.get("price_snapshot").toString().contains("never-copy-key"));
        assertFalse(row.get("price_snapshot").toString().contains("secret-query"));
    }

    @Test
    void U03BigDecimalCostUsesDisjointInputAndDoesNotCountReasoningTwice() {
        assertEquals(
                new BigDecimal("0.00012200"),
                PriceCalculator.reported(REPORTED, PriceCalculator.Prices.from(prices)));
        var noReasoning = new ModelUsage(100L, 10L, 20L, 0L, null, UsageStatus.REPORTED);
        assertEquals(
                PriceCalculator.reported(REPORTED, PriceCalculator.Prices.from(prices)),
                PriceCalculator.reported(noReasoning, PriceCalculator.Prices.from(prices)));
        var store = store(prices, CLOCK);
        var request = request(UUID.randomUUID(), Purpose.CHAT);
        store.begin(request);
        store.dispatched(request.callId());
        store.usage(request.callId(), REPORTED);
        var changed =
                new MemoryProperties(
                        8000,
                        800,
                        32768,
                        1024,
                        1000,
                        "USD",
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        "other");
        store(changed, CLOCK).finish(request.callId(), "SUCCEEDED", null);
        assertEquals(
                new BigDecimal("0.00012200"),
                amount(
                        fixture.db
                                .one(
                                        "select reported_cost from model_call where id=?",
                                        request.callId())
                                .get("reported_cost")));
    }

    @Test
    void U04MissingNonzeroCachePriceMakesAmountUnknownRatherThanFree() {
        var missing = settings("USD", "v1", null);
        var usage = new ModelUsage(100L, 10L, 20L, 5L, 3L, UsageStatus.REPORTED);
        assertNull(PriceCalculator.reported(usage, PriceCalculator.Prices.from(missing)));
        var store = store(missing, CLOCK);
        var request = request(UUID.randomUUID(), Purpose.CHAT);
        store.begin(request);
        store.dispatched(request.callId());
        store.usage(request.callId(), usage);
        store.finish(request.callId(), "SUCCEEDED", null);
        var result = queries(missing).daily(groupId, LocalDate.of(2026, 11, 11));
        assertEquals(100L, result.totals().inputTokens());
        assertEquals(5L, result.totals().cacheWriteTokens());
        assertNull(result.totals().reportedCost());
        assertEquals(1L, result.totals().reportedCalls());
    }

    @Test
    void U05SentTimeoutIsUnknownAndPreDispatchFailureIsDefinitelyZero() {
        var delegate = mock(TextGenerator.class);
        when(delegate.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenAnswer(
                        invocation -> {
                            GenerationRequest request = invocation.getArgument(0);
                            GenerationObserver observer = invocation.getArgument(1);
                            if (request.purpose() == Purpose.CHAT) {
                                observer.dispatched();
                                observer.usage(
                                        new ModelUsage(
                                                30L, null, null, null, null, UsageStatus.UNKNOWN));
                            }
                            throw new IllegalStateException("PI_DEADLINE");
                        });
        var generator = new MeteredTextGenerator(delegate, store(prices, CLOCK));
        var sent = request(UUID.randomUUID(), Purpose.CHAT);
        var notSent = request(UUID.randomUUID(), Purpose.DAILY_SUMMARY);
        assertThrows(
                IllegalStateException.class,
                () -> generator.generate(sent, GenerationObserver.noop()));
        assertThrows(
                IllegalStateException.class,
                () -> generator.generate(notSent, GenerationObserver.noop()));
        var unknown = fixture.db.one("select * from model_call where id=?", sent.callId());
        assertTrue((Boolean) unknown.get("dispatched"));
        assertEquals("UNKNOWN", unknown.get("usage_status"));
        assertEquals(30L, unknown.get("input_tokens"));
        assertNull(unknown.get("reported_cost"));
        assertEquals(
                PriceCalculator.estimated(
                        TokenEstimator.estimateInput(sent.systemPrompt(), sent.userPrompt()),
                        sent.maxOutputTokens(),
                        PriceCalculator.Prices.from(prices)),
                amount(unknown.get("estimated_cost")));
        var zero = fixture.db.one("select * from model_call where id=?", notSent.callId());
        assertFalse((Boolean) zero.get("dispatched"));
        assertEquals(new BigDecimal("0.00000000"), amount(zero.get("reported_cost")));
        assertNull(zero.get("estimated_cost"));
    }

    @Test
    void U06LedgerInsertFailurePreventsAnyDelegateInteraction() {
        var store = mock(ModelCallStore.class);
        var delegate = mock(TextGenerator.class);
        var failure = new IllegalStateException("LEDGER_UNAVAILABLE");
        doThrow(failure).when(store).begin(any());
        var generator = new MeteredTextGenerator(delegate, store);
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                generator.generate(
                                        request(UUID.randomUUID(), Purpose.CHAT),
                                        GenerationObserver.noop())));
        verifyNoInteractions(delegate);
    }

    @Test
    void U05SettlementSeparatesDispatchUsageAndBusinessTerminalStates() {
        for (String scenario :
                List.of(
                        "REPORTED_FAILURE",
                        "REPORTED_CANCEL",
                        "UNKNOWN_TIMEOUT",
                        "NOT_SENT",
                        "BACKGROUND_BUSY")) {
            var request = request(UUID.randomUUID(), Purpose.CHAT);
            var delegate = mock(TextGenerator.class);
            RuntimeException failure =
                    scenario.equals("REPORTED_CANCEL")
                            ? new CancellationException("cancelled")
                            : new IllegalStateException(
                                    scenario.equals("BACKGROUND_BUSY")
                                            ? "BACKGROUND_BUSY"
                                            : "PI_DEADLINE");
            boolean sent = !Set.of("NOT_SENT", "BACKGROUND_BUSY").contains(scenario),
                    known = scenario.startsWith("REPORTED_");
            when(delegate.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                    .thenAnswer(
                            invocation -> {
                                GenerationObserver observer = invocation.getArgument(1);
                                if (sent) {
                                    observer.dispatched();
                                }
                                if (known) {
                                    observer.usage(REPORTED);
                                }
                                throw failure;
                            });
            assertSame(
                    failure,
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    new MeteredTextGenerator(delegate, store(prices, CLOCK))
                                            .generate(request, GenerationObserver.noop())),
                    scenario);
            var rows = fixture.db.list("select * from model_call where run_id=?", request.runId());
            assertEquals(1, rows.size());
            var row = rows.get(0);
            assertEquals(
                    scenario.equals("REPORTED_CANCEL") ? "CANCELLED" : "FAILED",
                    row.get("state"),
                    scenario);
            assertEquals(sent, row.get("dispatched"), scenario);
            assertEquals(known ? "REPORTED" : "UNKNOWN", row.get("usage_status"), scenario);
            for (String column :
                    List.of(
                            "input_tokens",
                            "output_tokens",
                            "cache_read_tokens",
                            "cache_write_tokens")) {
                if (!known) {
                    assertNull(row.get(column), scenario + " " + column);
                }
            }
            if (known) {
                assertEquals(100L, row.get("input_tokens"));
                assertEquals(10L, row.get("output_tokens"));
                assertEquals(20L, row.get("cache_read_tokens"));
                assertEquals(0L, row.get("cache_write_tokens"));
                assertEquals(new BigDecimal("0.00012200"), amount(row.get("reported_cost")));
                assertNull(row.get("estimated_cost"));
            } else if (sent) {
                assertNull(row.get("reported_cost"));
                assertNotNull(row.get("estimated_cost"));
            } else {
                assertEquals(new BigDecimal("0.00000000"), amount(row.get("reported_cost")));
                assertNull(row.get("estimated_cost"));
            }
            verify(delegate, times(1))
                    .generate(any(GenerationRequest.class), any(GenerationObserver.class));
        }
    }

    @Test
    void U06RunningLedgerIsCommittedBeforeExternalCall() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delegate = mock(TextGenerator.class);
        var executor = Executors.newSingleThreadExecutor();
        when(delegate.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenAnswer(
                        invocation -> {
                            GenerationObserver observer = invocation.getArgument(1);
                            observer.dispatched();
                            entered.countDown();
                            assertTrue(release.await(10, TimeUnit.SECONDS));
                            observer.usage(REPORTED);
                            return new TextGenerator.Result("回答", null, REPORTED);
                        });
        var request = request(UUID.randomUUID(), Purpose.CHAT);
        var generator = new MeteredTextGenerator(delegate, store(prices, CLOCK));
        try {
            var future =
                    executor.submit(() -> generator.generate(request, GenerationObserver.noop()));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            try (var independent = fixture.source.getConnection();
                    var statement =
                            independent.prepareStatement(
                                    "select state,dispatched from model_call where cast(id as text)=?")) {
                statement.setString(1, request.callId().toString());
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("RUNNING", result.getString("state"));
                    assertTrue(result.getBoolean("dispatched"));
                    assertFalse(result.next());
                }
            }
            release.countDown();
            assertEquals("回答", future.get(10, TimeUnit.SECONDS).text());
            assertEquals(
                    "SUCCEEDED",
                    fixture.db
                            .one("select state from model_call where id=?", request.callId())
                            .get("state"));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void U07ShanghaiDateUsesCreatedTimeAndNeverLeaksOtherGroup() {
        var before = Clock.fixed(Instant.parse("2026-11-11T15:59:59Z"), ZoneOffset.UTC);
        var after = Clock.fixed(Instant.parse("2026-11-11T16:00:01Z"), ZoneOffset.UTC);
        var request = request(UUID.randomUUID(), Purpose.MEMORY_QUERY);
        var starting = store(prices, before);
        starting.begin(request);
        starting.dispatched(request.callId());
        var finishing = store(prices, after);
        finishing.usage(request.callId(), REPORTED);
        finishing.finish(request.callId(), "SUCCEEDED", null);
        var next = request(UUID.randomUUID(), Purpose.DAILY_SUMMARY);
        var midnight =
                store(prices, Clock.fixed(Instant.parse("2026-11-11T16:00:00Z"), ZoneOffset.UTC));
        midnight.begin(next);
        midnight.dispatched(next.callId());
        midnight.usage(next.callId(), REPORTED);
        midnight.finish(next.callId(), "SUCCEEDED", null);
        UUID otherGroup = fixture.group("other");
        var other =
                new GenerationRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        otherGroup,
                        2,
                        Purpose.CHAT,
                        "system",
                        "user",
                        8000,
                        800,
                        5);
        starting.begin(other);
        starting.dispatched(other.callId());
        starting.usage(other.callId(), REPORTED);
        starting.finish(other.callId(), "SUCCEEDED", null);
        var day = queries(prices).daily(groupId, LocalDate.of(2026, 11, 11));
        var tomorrow = queries(prices).daily(groupId, LocalDate.of(2026, 11, 12));
        assertEquals(1L, day.totals().calls());
        assertEquals(1L, tomorrow.totals().calls());
        assertEquals(1L, purpose(day, Purpose.MEMORY_QUERY).calls());
        assertEquals(0L, purpose(day, Purpose.CHAT).calls());
        assertEquals("Asia/Shanghai", day.zone());
        assertEquals("2026-11-11", day.date());
    }

    @Test
    void U08EmptyUnknownAndMixedDaysHaveDifferentTotals() {
        var queries = queries(prices);
        var date = LocalDate.of(2026, 11, 11);
        var empty = queries.daily(groupId, date);
        assertEquals(0L, empty.totals().calls());
        assertEquals(new BigDecimal("0.00000000"), empty.totals().reportedCost());
        assertEquals(new BigDecimal("0.00000000"), empty.totals().estimatedCost());
        var store = store(prices, CLOCK);
        var unknown = request(UUID.randomUUID(), Purpose.MEMORY_SUMMARY);
        store.begin(unknown);
        store.dispatched(unknown.callId());
        store.usage(
                unknown.callId(), new ModelUsage(30L, null, null, null, null, UsageStatus.UNKNOWN));
        store.finish(unknown.callId(), "FAILED", "PI_DEADLINE");
        var allUnknown = queries.daily(groupId, date);
        assertNull(allUnknown.totals().reportedCost());
        assertEquals(1L, allUnknown.totals().unknownCalls());
        assertEquals(30L, allUnknown.totals().inputTokens());
        var reported = request(UUID.randomUUID(), Purpose.CHAT);
        store.begin(reported);
        store.dispatched(reported.callId());
        store.usage(reported.callId(), REPORTED);
        store.finish(reported.callId(), "SUCCEEDED", null);
        var busy = request(UUID.randomUUID(), Purpose.DAILY_SUMMARY);
        store.begin(busy);
        store.finish(busy.callId(), "FAILED", "BACKGROUND_BUSY");
        var mixed = queries.daily(groupId, date);
        assertEquals(3L, mixed.totals().calls());
        assertEquals(2L, mixed.totals().dispatchedCalls());
        assertEquals(1L, mixed.totals().reportedCalls());
        assertEquals(1L, mixed.totals().unknownCalls());
        assertEquals(130L, mixed.totals().inputTokens());
        assertEquals(new BigDecimal("0.00012200"), mixed.totals().reportedCost());
        assertNotNull(mixed.totals().estimatedCost());
        assertEquals(5, mixed.byPurpose().size());
        long calls = 0;
        for (Purpose purpose : Purpose.values()) {
            calls += purpose(mixed, purpose).calls();
        }
        assertEquals(mixed.totals().calls(), calls);
    }

    @Test
    void U08MixedVersionsSumOnlySameCurrencyAndPartialResultKeepsKnownFields() {
        for (var settings : List.of(prices, settings("USD", "v2", new BigDecimal("1.25")))) {
            var store = store(settings, CLOCK);
            var request = request(UUID.randomUUID(), Purpose.CHAT);
            store.begin(request);
            store.dispatched(request.callId());
            store.usage(request.callId(), REPORTED);
            store.finish(request.callId(), "SUCCEEDED", null);
        }
        var sameCurrency = queries(prices).daily(groupId, LocalDate.of(2026, 11, 11));
        assertEquals("mixed", sameCurrency.totals().priceVersion());
        assertEquals(new BigDecimal("0.00024400"), sameCurrency.totals().reportedCost());
        var store = store(settings("CNY", "v3", new BigDecimal("1.25")), CLOCK);
        var request = request(UUID.randomUUID(), Purpose.CHAT);
        store.begin(request);
        store.dispatched(request.callId());
        store.usage(request.callId(), REPORTED);
        store.finish(request.callId(), "SUCCEEDED", null);
        var multipleCurrencies = queries(prices).daily(groupId, LocalDate.of(2026, 11, 11));
        assertEquals("mixed", multipleCurrencies.totals().currency());
        assertNull(multipleCurrencies.totals().reportedCost());
        assertNull(multipleCurrencies.totals().estimatedCost());
        var delegate = mock(TextGenerator.class);
        when(delegate.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenAnswer(
                        invocation -> {
                            GenerationObserver observer = invocation.getArgument(1);
                            observer.dispatched();
                            observer.usage(
                                    new ModelUsage(
                                            42L, null, null, null, null, UsageStatus.UNKNOWN));
                            return new TextGenerator.Result("回答", null);
                        });
        var result =
                new MeteredTextGenerator(delegate, store(prices, CLOCK))
                        .generate(
                                request(UUID.randomUUID(), Purpose.CHAT),
                                GenerationObserver.noop());
        assertEquals(42L, result.usage().inputTokens());
        assertEquals(UsageStatus.UNKNOWN, result.usage().status());
    }

    @Test
    void RecoveryRetentionAndCancellationPreserveAccounting() {
        var store = store(prices, CLOCK);
        var sent = request(UUID.randomUUID(), Purpose.CHAT);
        var waiting = request(UUID.randomUUID(), Purpose.MEMORY_SUMMARY);
        store.begin(sent);
        store.dispatched(sent.callId());
        store.begin(waiting);
        store.recover();
        assertEquals(
                "UNKNOWN",
                fixture.db
                        .one("select state from model_call where id=?", sent.callId())
                        .get("state"));
        var neverSent = fixture.db.one("select * from model_call where id=?", waiting.callId());
        assertEquals("FAILED", neverSent.get("state"));
        assertEquals(new BigDecimal("0.00000000"), amount(neverSent.get("reported_cost")));
        var old = request(UUID.randomUUID(), Purpose.CHAT);
        store(prices, Clock.fixed(NOW.minus(Duration.ofDays(90)).minusMillis(1), ZoneOffset.UTC))
                .begin(old);
        var boundary = request(UUID.randomUUID(), Purpose.CHAT);
        store(prices, Clock.fixed(NOW.minus(Duration.ofDays(90)), ZoneOffset.UTC)).begin(boundary);
        assertEquals(1, store.expire());
        assertNull(fixture.db.one("select id from model_call where id=?", old.callId()));
        assertNotNull(fixture.db.one("select id from model_call where id=?", boundary.callId()));
        var delegate = mock(TextGenerator.class);
        var cancelled = new CancellationException("private text must not become an error code");
        when(delegate.generate(any(GenerationRequest.class), any(GenerationObserver.class)))
                .thenAnswer(
                        invocation -> {
                            GenerationObserver observer = invocation.getArgument(1);
                            observer.dispatched();
                            observer.usage(REPORTED);
                            throw cancelled;
                        });
        var call = request(UUID.randomUUID(), Purpose.CHAT);
        assertSame(
                cancelled,
                assertThrows(
                        CancellationException.class,
                        () ->
                                new MeteredTextGenerator(delegate, store)
                                        .generate(call, GenerationObserver.noop())));
        var row = fixture.db.one("select * from model_call where id=?", call.callId());
        assertEquals("CANCELLED", row.get("state"));
        assertEquals("MODEL_CANCELLED", row.get("error_code"));
        assertEquals(new BigDecimal("0.00012200"), amount(row.get("reported_cost")));
        fixture.db.exec("delete from group_config where id=?", groupId);
        assertNull(
                fixture.db
                        .one("select group_id from model_call where id=?", call.callId())
                        .get("group_id"));
        assertEquals(
                404,
                assertThrows(
                                ResponseStatusException.class,
                                () -> queries(prices).daily(groupId, null))
                        .getStatusCode()
                        .value());
    }

    private GenerationRequest request(UUID runId, Purpose purpose) {
        return new GenerationRequest(
                runId, UUID.randomUUID(), groupId, 2, purpose, "system", "user", 8000, 800, 5);
    }

    private ModelCallStore store(MemoryProperties settings, Clock clock) {
        var gateway = new BotProperties.Gateway(false, "", "", "", "");
        var properties =
                new BotProperties(
                        "admin",
                        "",
                        directory,
                        false,
                        new BotProperties.Pi(
                                "node",
                                "unused",
                                "local-test",
                                "model",
                                "KEY",
                                "never-copy-key",
                                5,
                                "https://invalid.test/?secret-query"),
                        null,
                        new BotProperties.Qq(
                                gateway.enabled(),
                                new BotProperties.OfficialQq(
                                        gateway.accountId(),
                                        "",
                                        "https://api.bot.qq.com",
                                        "https://api.bot.qq.com/app/getAppAccessToken",
                                        false)),
                        gateway,
                        new BotProperties.Rag(false));
        return new ModelCallStore(fixture.db, properties, settings, clock);
    }

    private UsageQueries queries(MemoryProperties settings) {
        return new UsageQueries(fixture.db, settings, CLOCK);
    }

    private MemoryProperties settings(String currency, String version, BigDecimal cacheWrite) {
        return new MemoryProperties(
                8000,
                800,
                32768,
                1024,
                1000,
                currency,
                BigDecimal.ONE,
                new BigDecimal("2"),
                new BigDecimal("0.1"),
                cacheWrite,
                version);
    }

    private BigDecimal amount(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private UsageQueries.Totals purpose(UsageQueries.DailyUsage result, Purpose purpose) {
        return result.byPurpose().get(purpose.name());
    }
}
