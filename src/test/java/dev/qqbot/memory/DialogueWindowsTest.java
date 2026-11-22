/**
 * [INPUT]: 提供短确认、重复消息和跨间隔的有序原文。
 * [OUTPUT]: 验证窗口保留来源及前文的边界。
 * [POS]: 对话窗口的纯规则测试。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.qqbot.memory.RetrievalGate.DialogueMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DialogueWindowsTest {
    @Test
    void build_keepsOverlapAcrossCapacityButNotAcrossSessionGap() {
        var first = message(0, "发布会明天开始");
        var second = message(20, "收到");
        var third = message(400, "结论已经整理");
        var windows = RetrievalGate.buildDialogueWindows(List.of(first, second, third), 1, 200, 60);

        assertEquals(List.of(first.id()), windows.get(0).sourceIds());
        assertEquals(List.of(first.id(), second.id()), windows.get(1).sourceIds());
        assertEquals(List.of(third.id()), windows.get(2).sourceIds());
        assertEquals(third.body(), windows.get(2).body());
    }

    @Test
    void build_rejectsAckWithoutContextAndDuplicateWithDifferentPunctuation() {
        var ack = message(0, "收到");
        var first = message(10, "安排已确定！");
        var duplicate = message(130, "安排 已确定");
        var windows =
                RetrievalGate.buildDialogueWindows(List.of(ack, first, duplicate), 6, 500, 300);

        assertEquals(1, windows.size());
        assertEquals(List.of(first.id()), windows.get(0).sourceIds());
    }

    private static DialogueMessage message(long second, String body) {
        return new DialogueMessage(
                UUID.randomUUID(), "member", "成员", body, Instant.ofEpochSecond(second));
    }
}
