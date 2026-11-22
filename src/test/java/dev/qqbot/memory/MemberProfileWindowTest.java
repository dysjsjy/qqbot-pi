/**
 * [INPUT]: 提供固定时间、群水位和已认领画像快照。
 * [OUTPUT]: 校验首次、增量、重放和冷留存的证据边界。
 * [POS]: 画像证据的纯规划测试，不需要 SQLite。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.qqbot.RetentionProperties;
import dev.qqbot.memory.MemberProfileEvidence.Progress;
import dev.qqbot.memory.MemberProfileEvidence.Range;
import dev.qqbot.memory.MemberProfileExtractor.Cycle;
import dev.qqbot.memory.MemberProfileExtractor.Job;
import dev.qqbot.memory.MemberProfileExtractor.JobType;
import dev.qqbot.memory.MemberProfileExtractor.Work;
import dev.qqbot.memory.MemberProfileJobs.Snapshot;
import dev.qqbot.memory.MemberProfileJobs.Snapshot.Identity;
import dev.qqbot.memory.MemberProfilePolicyService.Policy;
import dev.qqbot.memory.MemberProfilePolicyService.Scope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemberProfileWindowTest {
    private static final Instant NOW = Instant.parse("2026-11-22T08:00:00Z");
    private static final long FLOOR = 20;
    private static final long WATERMARK = 60;
    private static final long GROUP_TIP = 120;
    private static final long REPLAY_TIP = 100;
    private static final int COLD_START_DAYS = 90;
    private static final int ARCHIVE_DAYS = 45;
    private static final Policy POLICY =
            new Policy(true, false, 20, 20, 3, COLD_START_DAYS, 15, 24, 10, 60, "SUBTLE", 0L);
    private static final RetentionProperties RETENTION =
            new RetentionProperties(30, ARCHIVE_DAYS, 30);

    @Test
    void planWindow_firstRunStopsAtFloorAndFiniteRetention() {
        var progress = new Progress(0, null, null, FLOOR);
        var window = MemberProfileEvidence.planWindow(work(JobType.EXTRACT, progress), RETENTION);

        assertThat(window.range()).isEqualTo(new Range(FLOOR, GROUP_TIP));
        assertThat(window.cutoff()).isEqualTo(NOW.minus(Duration.ofDays(ARCHIVE_DAYS)));
        assertThat(window.isReplaying()).isTrue();
        assertThat(window.isReset()).isFalse();
    }

    @Test
    void planWindow_regularUpdateStartsAfterLastWatermark() {
        var progress = new Progress(WATERMARK, null, NOW.minus(Duration.ofDays(1)), FLOOR);
        var window = MemberProfileEvidence.planWindow(work(JobType.EXTRACT, progress), RETENTION);

        assertThat(window.range()).isEqualTo(new Range(WATERMARK, GROUP_TIP));
        assertThat(window.cutoff()).isEqualTo(NOW.minus(Duration.ofDays(30)));
        assertThat(window.isReplaying()).isFalse();
    }

    @Test
    void planWindow_continuesReplayAtItsFrozenUpperBound() {
        var progress = new Progress(WATERMARK, REPLAY_TIP, NOW, FLOOR);
        var window = MemberProfileEvidence.planWindow(work(JobType.EXTRACT, progress), RETENTION);

        assertThat(window.range()).isEqualTo(new Range(WATERMARK, REPLAY_TIP));
        assertThat(window.isReplaying()).isTrue();
    }

    @Test
    void planWindow_explicitReplayStartsAtFloorAndFreezesNewUpperBound() {
        var progress = new Progress(WATERMARK, REPLAY_TIP, NOW, FLOOR);
        var window = MemberProfileEvidence.planWindow(work(JobType.REPLAY, progress), RETENTION);

        assertThat(window.range()).isEqualTo(new Range(FLOOR, GROUP_TIP));
        assertThat(window.isReplaying()).isTrue();
        assertThat(window.isReset()).isTrue();
    }

    @Test
    void reachesUpdateThreshold_countsOnlyNonEmptySourcesAndAllowsTokenTrigger() {
        assertThat(MemberProfileEvidence.reachesUpdateThreshold(List.of("线索", ""), 2)).isFalse();
        assertThat(MemberProfileEvidence.reachesUpdateThreshold(List.of("线索", "证据"), 2)).isTrue();
        assertThat(MemberProfileEvidence.reachesUpdateThreshold(List.of("线索".repeat(1_600)), 2))
                .isTrue();
    }

    private static Work work(JobType type, Progress progress) {
        var identity = new Identity(UUID.randomUUID(), "member", 0);
        var member = new Snapshot(identity, progress, null);
        var job = new Job(UUID.randomUUID(), type, identity.id(), identity.sender());
        var cycle = new Cycle(new Scope(UUID.randomUUID(), 1, GROUP_TIP, true), POLICY, NOW);
        return new Work(job, member, cycle);
    }
}
