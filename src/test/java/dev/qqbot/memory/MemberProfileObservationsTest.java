/**
 * [INPUT]: 提供模型操作、已存观测和固定时间的本人证据。
 * [OUTPUT]: 校验证码去重、确认窗口和替换关系。
 * [POS]: 画像观测更新计划的纯逻辑测试。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.qqbot.memory.MemberProfileEvidence.Cue;
import dev.qqbot.memory.MemberProfileEvidence.Source;
import dev.qqbot.memory.MemberProfileObservations.Evidence;
import dev.qqbot.memory.MemberProfileObservations.Identity;
import dev.qqbot.memory.MemberProfileObservations.Observation;
import dev.qqbot.memory.MemberProfileObservations.Update;
import dev.qqbot.memory.ProfileGuardrails.Action;
import dev.qqbot.memory.ProfileGuardrails.Category;
import dev.qqbot.memory.ProfileGuardrails.Op;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemberProfileObservationsTest {
    private static final Instant FIRST = Instant.parse("2026-11-22T02:00:00Z");
    private static final int PROFILE_TTL_DAYS = 60;
    private static final String STATEMENT = "分享摄影经验";

    @Test
    void planUpdate_duplicateSourcesDoNotRefreshEvidenceOrExpiry() {
        var first = source(Duration.ZERO);
        var existing = observation(first);
        var operation = operation(Action.REINFORCE, existing.identity().id(), List.of(first));

        assertThat(
                        MemberProfileObservations.planUpdate(
                                PROFILE_TTL_DAYS, operation, existing, List.of(first)))
                .isEmpty();
    }

    @Test
    void planUpdate_confirmsThreeIndependentHourlyWindowsWithinOneDay() {
        var first = source(Duration.ZERO);
        var existing = observation(first);
        var later = List.of(source(Duration.ofHours(1)), source(Duration.ofHours(2)));
        var operation = operation(Action.REINFORCE, existing.identity().id(), later);
        Update result = requireUpdate(operation, existing, later);

        assertThat(result.id()).isEqualTo(existing.identity().id());
        assertThat(result.revision().isConfirmed()).isTrue();
        assertThat(result.evidence().count()).isEqualTo(3);
        assertThat(result.evidence().values().days()).hasSize(1);
        assertThat(result.evidence().values().windows()).hasSize(3);
        assertThat(result.expiresAt())
                .isEqualTo(FIRST.plus(Duration.ofHours(2)).plus(Duration.ofDays(PROFILE_TTL_DAYS)));
    }

    @Test
    void planUpdate_replacementRetainsTargetButEvaluatesItsOwnEvidence() {
        UUID replaced = UUID.randomUUID();
        var sameWindow = List.of(source(Duration.ZERO), source(Duration.ofMinutes(10)));
        var firstAttempt = operation(Action.UPDATE, replaced, sameWindow);
        Update candidate = requireUpdate(firstAttempt, null, sameWindow);

        assertThat(candidate.revision().supersedes()).isEqualTo(replaced);
        assertThat(candidate.revision().isConfirmed()).isFalse();

        var nextDay = List.of(sameWindow.get(0), source(Duration.ofDays(1)));
        Update confirmed =
                requireUpdate(operation(Action.UPDATE, replaced, nextDay), null, nextDay);

        assertThat(confirmed.revision().supersedes()).isEqualTo(replaced);
        assertThat(confirmed.revision().isConfirmed()).isTrue();
    }

    private static Source source(Duration afterFirst) {
        return new Source(
                new Cue(UUID.randomUUID(), afterFirst.getSeconds(), FIRST.plus(afterFirst)),
                "本人证据",
                "");
    }

    private static Observation observation(Source source) {
        var identity =
                new Identity(UUID.randomUUID(), Category.EXPERTISE.name(), STATEMENT, "CANDIDATE");
        var evidence = MemberProfileObservations.mergeEvidence(Evidence.empty(), List.of(source));
        return new Observation(identity, evidence, 0.5, null);
    }

    private static Op operation(Action action, UUID target, List<Source> cited) {
        return new Op(
                action,
                target,
                Category.EXPERTISE,
                STATEMENT,
                0.75,
                cited.stream().map(Source::id).toList());
    }

    private static Update requireUpdate(Op operation, Observation match, List<Source> cited) {
        return MemberProfileObservations.planUpdate(PROFILE_TTL_DAYS, operation, match, cited)
                .orElseThrow();
    }
}
