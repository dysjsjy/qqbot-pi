/**
 * [INPUT]: 提供冻结来源、现有事实和模型读编结果。
 * [OUTPUT]: 校验紧凑处置顺序、显式引用闭合和关键事实降级审阅。
 * [POS]: 日报读批和编辑校验的纯逻辑测试。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.qqbot.schedule.DailySummaryFacts.Disposition;
import dev.qqbot.schedule.DailySummaryFacts.Fact;
import dev.qqbot.schedule.DailySummaryFacts.InvalidResult;
import dev.qqbot.schedule.DailySummaryFacts.Kind;
import dev.qqbot.schedule.DailySummaryFacts.SourceUnit;
import dev.qqbot.schedule.DailySummaryFacts.Value;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DailySummaryReadEditTest {
    private static final String FIRST = "u1";
    private static final String SECOND = "u2";
    private static final String THIRD = "u3";
    private static final String SPEAKER = "阿青";
    private static final String DEADLINE = "周五";
    private static final String COMMITMENT = "阿青周五提交计划";
    private static final String FACT =
            """
            {"kind":"ACTION","text":"阿青周五提交计划","topic":"计划","owner":"@阿青","due":"周五","units":[%s]}
            """;
    private final List<SourceUnit> units = List.of(unit(FIRST, 1), unit(SECOND, 2), unit(THIRD, 3));

    @Test
    void read_compactDispositionsKeepFactLinksAndFillMissingChatter() {
        String response =
                """
                {"facts":[%s],"ignored":{"ACK":["u2","u1"]}}
                """
                        .formatted(FACT.formatted("\"u1\""));

        var result = DailySummaryReading.read(response, units, 5);

        assertThat(result.dispositions())
                .containsExactly(
                        new Disposition(FIRST, List.of("f5"), ""),
                        new Disposition(SECOND, List.of(), "ACK"),
                        new Disposition(THIRD, List.of(), "CHAT"));
        assertThat(result.facts()).singleElement().extracting(Fact::owner).isEqualTo(SPEAKER);
    }

    @Test
    void read_explicitDispositionsRejectMissingBacklink() {
        String response =
                """
                {"facts":[%s],"dispositions":[{"unit":"u1","facts":[0]},
                 {"unit":"u2","reason":"CHAT"},{"unit":"u3","reason":"ACK"}]}
                """
                        .formatted(FACT.formatted("\"u1\",\"u2\""));

        assertThatThrownBy(() -> DailySummaryReading.read(response, units, 1))
                .isInstanceOf(InvalidResult.class)
                .hasMessage("RESPONSE_FORMAT");
    }

    @Test
    void revise_requiresReviewToDowngradeRequiredFact() {
        var inputs =
                List.of(
                        new Fact(
                                "f1",
                                Kind.ACTION,
                                COMMITMENT,
                                "计划",
                                SPEAKER,
                                DEADLINE,
                                Set.of(units.get(0).messageId()),
                                Value.REQUIRED,
                                "DEADLINE"));
        var rules = new DailySummaryEditing.Rules(2, true, false);
        String response =
                """
                {"facts":[{"inputs":["f1"],"value":"OPTIONAL","reason":"USEFUL","detail":"BRIEF"}]}
                """;

        assertThatThrownBy(() -> DailySummaryEditing.revise(response, inputs, rules))
                .isInstanceOf(InvalidResult.class)
                .hasMessage("REQUIRED_FACT_MISSING");
    }

    private static SourceUnit unit(String id, int order) {
        return new SourceUnit(
                id,
                UUID.randomUUID().toString(),
                order,
                0,
                COMMITMENT.length(),
                "",
                SPEAKER,
                COMMITMENT);
    }
}
