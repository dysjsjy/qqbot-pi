/**
 * [INPUT]: 提供可信事实与选材、成文阶段的模型 JSON。
 * [OUTPUT]: 校验选材覆盖、匿名署名约束、截止时间与重复段落事实。
 * [POS]: 日报模型输出的纯校验测试，独立于生成器和尝试状态。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.qqbot.schedule.DailySummaryFacts.Choice;
import dev.qqbot.schedule.DailySummaryFacts.Fact;
import dev.qqbot.schedule.DailySummaryFacts.InvalidResult;
import dev.qqbot.schedule.DailySummaryFacts.Kind;
import dev.qqbot.schedule.DailySummaryFacts.Value;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DailySummaryOutputTest {
    private static final String SOURCE = "1a600acd-313f-4daa-9c4e-50128b8aa090";
    private static final Fact ACTION =
            new Fact(
                    "f1",
                    Kind.ACTION,
                    "阿青周五提交计划。",
                    "计划",
                    "阿青",
                    "周五",
                    Set.of(SOURCE),
                    Value.REQUIRED,
                    "DEADLINE");
    private static final List<Fact> FACTS = List.of(ACTION);
    private static final DailySummaryWriting.Rules ANONYMOUS =
            new DailySummaryWriting.Rules(100, true, Set.of(ACTION.owner()));

    @Test
    void readSelection_preservesDetailForRequiredFact() {
        var choices =
                DailySummarySelection.readSelection(
                        """
                {"selected":[{"id":"f1","detail":"FULL"}],"omitted":[]}
                """,
                        FACTS);

        assertThat(choices).containsExactly(new Choice(ACTION.id(), "FULL", ""));
    }

    @Test
    void readSelection_rejectsOmissionOfRequiredFact() {
        assertThatThrownBy(
                        () ->
                                DailySummarySelection.readSelection(
                                        """
                {"selected":[],"omitted":[{"id":"f1","reason":"BACKGROUND"}]}
                """,
                                        FACTS))
                .isInstanceOf(InvalidResult.class)
                .hasMessage("REQUIRED_FACT_MISSING");
    }

    @Test
    void validate_anonymousReportRequiresDeadlineAndRejectsSpeaker() {
        var written = DailySummaryWriting.validate(report(paragraph("周五提交计划。")), FACTS, ANONYMOUS);
        assertThat(written.paragraphs()).hasSize(1);

        assertThatThrownBy(
                        () ->
                                DailySummaryWriting.validate(
                                        report(paragraph("按时提交计划。")), FACTS, ANONYMOUS))
                .isInstanceOf(InvalidResult.class)
                .hasMessage("REQUIRED_FACT_MISSING");
        assertThatThrownBy(
                        () ->
                                DailySummaryWriting.validate(
                                        report(paragraph(ACTION.text())), FACTS, ANONYMOUS))
                .isInstanceOf(InvalidResult.class)
                .hasMessage("SPEAKER_LEAKED");
    }

    @Test
    void validate_rejectsFactUsedInTwoParagraphs() {
        String first = paragraph("周五提交计划。");
        String raw = report(first + "," + first);

        assertThatThrownBy(() -> DailySummaryWriting.validate(raw, FACTS, ANONYMOUS))
                .isInstanceOf(InvalidResult.class)
                .hasMessage("DUPLICATE_FACTS");
    }

    private static String report(String paragraphs) {
        return "{\"paragraphs\":[" + paragraphs + "]}";
    }

    private static String paragraph(String text) {
        return """
                {"text":"%s","facts":["%s"],"sources":["%s"]}
                """
                .formatted(text, ACTION.id(), SOURCE);
    }
}
