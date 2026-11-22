/**
 * [INPUT]: 提供确认观测与模型选中的观测 ID 顺序。
 * [OUTPUT]: 校验卡片的顺序、去重、置信度与未知观测拒绝。
 * [POS]: 卡片组装的纯逻辑测试，和模型调用及发布事务独立。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.qqbot.memory.MemberProfileObservations.Evidence;
import dev.qqbot.memory.MemberProfileObservations.Identity;
import dev.qqbot.memory.MemberProfileObservations.Observation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemberProfileCardTest {
    @Test
    void buildCard_preservesSelectedOrderAndAveragesOnlyDistinctStatements() {
        var photography = observation("分享摄影经验", 0.25);
        var hiking = observation("交流徒步路线", 0.75);
        var duplicate = observation("分享摄影经验", 1);
        var picked = List.of(id(hiking), id(photography), id(duplicate));

        var card =
                MemberProfileExtractor.buildCard(List.of(photography, duplicate, hiking), picked);

        assertThat(card.text()).isEqualTo("交流徒步路线；分享摄影经验");
        assertThat(card.score()).isEqualTo(0.5);
    }

    @Test
    void buildCard_rejectsIdOutsideOfferedObservations() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> MemberProfileExtractor.buildCard(List.of(), List.of(unknown)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(unknown.toString());
    }

    private static UUID id(Observation observation) {
        return observation.identity().id();
    }

    private static Observation observation(String statement, double confidence) {
        var identity =
                new Identity(
                        UUID.randomUUID(),
                        "EXPERTISE",
                        statement,
                        MemberProfileObservations.CONFIRMED);
        return new Observation(identity, Evidence.empty(), confidence, null);
    }
}
