/**
 * [INPUT]: 使用记忆值对象、边界样本及可变集合夹具。
 * [OUTPUT]: 验证不可变性、值约束及画像预算层默认值。
 * [POS]: 记忆值契约测试；不访问平台。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;

import dev.qqbot.memory.*;
import dev.qqbot.model.ModelUsage;
import dev.qqbot.model.UsageStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class T01ContractTest {
    @ParameterizedTest(name = "F06 {0}={1} accepted={2}")
    @CsvSource({
        "input,1023,false", "input,1024,true", "input,32768,true", "input,32769,false",
        "output,127,false", "output,128,true", "output,4096,true", "output,4097,false",
        "summary,255,false", "summary,256,true", "summary,4096,true", "summary,4097,false",
        "safety,255,false", "safety,256,true", "safety,8192,true", "safety,8193,false"
    })
    void F06EveryQuotaHasBothAllowedEndpointsAndAdjacentRejectedValues(
            String field, int value, boolean accepted) {
        if (accepted) {
            var actual = assertDoesNotThrow(() -> quota(field, value));
            int bound =
                    switch (field) {
                        case "input" -> actual.inputTokens();
                        case "output" -> actual.outputTokens();
                        case "summary" -> actual.summaryOutputTokens();
                        case "safety" -> actual.safetyTokens();
                        default -> throw new IllegalArgumentException(field);
                    };
            assertEquals(value, bound);
        } else {
            assertEquals(
                    "INVALID_MODEL_BUDGET",
                    assertThrows(IllegalArgumentException.class, () -> quota(field, value))
                            .getMessage());
        }
    }

    @ParameterizedTest(name = "F06 price field {0} value {1}")
    @MethodSource("prices")
    void F06EveryPriceDistinguishesMissingZeroPositiveAndNegative(int field, String value) {
        BigDecimal price = value.equals("missing") ? null : new BigDecimal(value);
        var configured = new BigDecimal[4];
        configured[field] = price;
        if (price != null && price.signum() < 0) {
            assertThrows(IllegalArgumentException.class, () -> priceSettings(configured));
        } else {
            var actual = assertDoesNotThrow(() -> priceSettings(configured));
            var observed =
                    new BigDecimal[] {
                        actual.inputPrice(),
                        actual.outputPrice(),
                        actual.cacheReadPrice(),
                        actual.cacheWritePrice()
                    };
            assertArrayEquals(configured, observed);
        }
    }

    @ParameterizedTest(name = "F06 output={0} summary={1} window={2} accepted={3}")
    @CsvSource({
        "800,1000,10023,false",
        "800,1000,10024,true",
        "1200,1000,10223,false",
        "1200,1000,10224,true"
    })
    void F06WindowUsesTheLargerOfChatAndSummaryOutput(
            int output, int summary, int window, boolean accepted) {
        if (accepted) {
            assertEquals(
                    window,
                    assertDoesNotThrow(() -> windowSettings(output, summary, window))
                            .contextWindow());
        } else {
            assertEquals(
                    "INVALID_MODEL_BUDGET",
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> windowSettings(output, summary, window))
                            .getMessage());
        }
    }

    @Test
    void F06AllSixNonemptyCollectionInputsAreCopiedAndAccessorsRejectMutation() {
        UUID source = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var sourceIds = new ArrayList<>(List.of(source));
        var evidence =
                new Evidence(
                        "message:" + source + ":0",
                        SourceKind.RAW,
                        "source",
                        sourceIds,
                        Instant.EPOCH,
                        1);
        sourceIds.clear();
        assertEquals(List.of(source), evidence.messageIds());
        assertThrows(UnsupportedOperationException.class, () -> evidence.messageIds().clear());
        assertEquals(List.of(source), evidence.messageIds());

        var layers = new LinkedHashMap<>(Map.of("system", 10));
        var selected = new ArrayList<>(List.of("retained"));
        var dropped = new ArrayList<>(List.of("excluded"));
        var reasons = new ArrayList<>(List.of("LAYER_BUDGET"));
        var stats = new ContextStats(10, layers, selected, dropped, reasons, "hash");
        layers.clear();
        selected.clear();
        dropped.clear();
        reasons.clear();
        assertEquals(Map.of("system", 10, "profile", 0), stats.layerTokens());
        assertEquals(List.of("retained"), stats.selectedKeys());
        assertEquals(List.of("excluded"), stats.droppedKeys());
        assertEquals(List.of("LAYER_BUDGET"), stats.reasons());
        assertThrows(
                UnsupportedOperationException.class, () -> stats.layerTokens().put("system", 20));
        assertThrows(UnsupportedOperationException.class, () -> stats.selectedKeys().clear());
        assertThrows(UnsupportedOperationException.class, () -> stats.droppedKeys().clear());
        assertThrows(UnsupportedOperationException.class, () -> stats.reasons().clear());
        assertEquals(Map.of("system", 10, "profile", 0), stats.layerTokens());
        assertEquals(List.of("retained"), stats.selectedKeys());
        assertEquals(List.of("excluded"), stats.droppedKeys());
        assertEquals(List.of("LAYER_BUDGET"), stats.reasons());

        var candidates = new ArrayList<>(List.of(evidence));
        var bundle =
                new ContextBundle("system", "question", candidates, stats, CitationMode.OPTIONAL);
        candidates.clear();
        assertEquals(List.of(evidence), bundle.evidence());
        assertThrows(UnsupportedOperationException.class, () -> bundle.evidence().clear());
        assertEquals(List.of(evidence), bundle.evidence());
    }

    @Test
    void F06EveryNullableContractFieldRetainsNull() {
        var scope =
                new MemoryScope(
                        UUID.fromString("22222222-2222-2222-2222-222222222222"),
                        2,
                        null,
                        100,
                        Instant.EPOCH,
                        Instant.EPOCH);
        assertNull(scope.triggerMessageId());
        var event =
                new HistoryEvent(
                        "source",
                        1,
                        Instant.EPOCH,
                        Role.USER,
                        "sender",
                        "name",
                        "body",
                        null,
                        null,
                        null,
                        false);
        assertNull(event.conversationId());
        assertNull(event.generation());
        assertNull(event.replyToKey());
        var usage = new ModelUsage(null, null, null, null, null, UsageStatus.UNKNOWN);
        assertNull(usage.inputTokens());
        assertNull(usage.outputTokens());
        assertNull(usage.cacheReadTokens());
        assertNull(usage.cacheWriteTokens());
        assertNull(usage.reasoningTokens());
        assertEquals(UsageStatus.UNKNOWN, usage.status());
    }

    private static Stream<Arguments> prices() {
        return java.util.stream.IntStream.range(0, 4)
                .boxed()
                .flatMap(
                        field ->
                                Stream.of("missing", "0", "0.1", "-0.1")
                                        .map(value -> Arguments.of(field, value)));
    }

    private MemoryProperties quota(String field, int value) {
        return new MemoryProperties(
                field.equals("input") ? value : 8000,
                field.equals("output") ? value : 800,
                65536,
                field.equals("safety") ? value : 1024,
                field.equals("summary") ? value : 1000,
                "USD",
                null,
                null,
                null,
                null,
                "test");
    }

    private MemoryProperties priceSettings(BigDecimal[] prices) {
        return new MemoryProperties(
                8000, 800, 65536, 1024, 1000, "USD", prices[0], prices[1], prices[2], prices[3],
                "test");
    }

    private MemoryProperties windowSettings(int output, int summary, int window) {
        return new MemoryProperties(
                8000, output, window, 1024, summary, "USD", null, null, null, null, "test");
    }
}
