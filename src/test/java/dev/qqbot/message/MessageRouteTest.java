/**
 * [INPUT]: 使用合成群范围和入站文本。
 * [OUTPUT]: 验证命令名、历史问题与普通群发言的分类。
 * [POS]: 不依赖入站状态、数据库或模型。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.message;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.qqbot.message.IngestService.MessageRoute;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageRouteTest {
    private final Group group = mock(Group.class);
    private final UUID messageId = UUID.randomUUID();

    @Test
    void classify_extractsOnlyTheQuestion_whenMemoryCommandHasArguments() {
        MessageRoute route = classify("  /memory \t 昨天何时开会？  ", true);

        assertTrue(route.isCommand());
        assertTrue(route.isHistoryQuery());
        assertEquals("昨天何时开会？", route.prompt());
        assertFalse(route.isOrdinaryGroupText());
    }

    @Test
    void classify_keepsUnknownCommand_whenNameOnlyStartsWithMemory() {
        MessageRoute route = classify("/memory-extra 昨天何时开会？", true);

        assertTrue(route.isCommand());
        assertFalse(route.isHistoryQuery());
        assertEquals("/memory-extra 昨天何时开会？", route.prompt());
    }

    @Test
    void classify_identifiesOrdinaryText_onlyWithoutMentionOrPrivateScope() {
        assertTrue(classify("普通群消息", false).isOrdinaryGroupText());
        assertFalse(classify("普通群消息", true).isOrdinaryGroupText());

        when(group.privateChat()).thenReturn(true);
        assertFalse(classify("私聊消息", false).isOrdinaryGroupText());
    }

    @Test
    void receipt_preservesScopeAndRejectsOnlyTheReply_whenCommandIsDeclined() {
        UUID groupId = UUID.randomUUID();
        when(group.id()).thenReturn(groupId);

        var receipt = classify("/profile status", false).receipt(false, true);

        assertEquals(groupId, receipt.groupId());
        assertEquals(messageId, receipt.messageId());
        assertFalse(receipt.duplicate());
        assertFalse(receipt.accepted());
        assertTrue(receipt.command());
    }

    private MessageRoute classify(String text, boolean isMentioned) {
        var request = mock(GroupIngestRequest.class);
        when(request.text()).thenReturn(text);
        when(request.mentionsBot()).thenReturn(isMentioned);
        return MessageRoute.classify(group, messageId, request);
    }
}
