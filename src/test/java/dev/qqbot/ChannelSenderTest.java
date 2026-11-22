package dev.qqbot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.qqbot.channel.ChannelSender;
import dev.qqbot.delivery.ChannelAdapter;
import dev.qqbot.delivery.MessageTransport.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelSenderTest {
    private ChannelAdapter adapter(String channel, boolean enabled, boolean privateMessages) {
        var adapter = mock(ChannelAdapter.class);
        when(adapter.channel()).thenReturn(channel);
        when(adapter.enabled()).thenReturn(enabled);
        when(adapter.supportsPrivateMessages()).thenReturn(privateMessages);
        return adapter;
    }

    @Test
    void disabledUnsupportedAndInvalidMessagesNeverReachAnAdapter() {
        var disabled = adapter("QQ", false, true);
        var bridge = adapter("WEIXIN", true, false);
        var sender = new ChannelSender(List.of(disabled, bridge));
        assertEquals(
                "GATEWAY_DISABLED",
                sender.send(new Message("QQ", "target", true, "text", "id", null)).code());
        assertEquals(
                "CHANNEL_UNSUPPORTED",
                sender.send(new Message("MISSING", "target", false, "text", "id", null)).code());
        assertEquals(
                "PRIVATE_CHANNEL_UNSUPPORTED",
                sender.send(new Message("WEIXIN", "target", true, "text", "id", null)).code());
        assertEquals(
                "TARGET_MISSING",
                sender.send(new Message("WEIXIN", " ", false, "text", "id", null)).code());
        verify(disabled, never()).send(any());
        verify(bridge, never()).send(any());
    }

    @Test
    void registeredExtensionReceivesCompleteMessageWithoutChangesToTheRouter() {
        var plugin = adapter("EXTENSION", true, true);
        var message =
                new Message(
                        "EXTENSION",
                        "user",
                        true,
                        "text",
                        "idempotency",
                        new Reply("source", Instant.now(), 2));
        var delivery = new Delivery(State.ACCEPTED, "receipt", null);
        when(plugin.send(message)).thenReturn(delivery);
        assertEquals(delivery, new ChannelSender(List.of(plugin)).send(message));
        verify(plugin).send(same(message));
    }

    @Test
    void duplicateOrUnnamedAdaptersFailAtRegistration() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        new ChannelSender(
                                List.of(adapter("QQ", true, true), adapter("QQ", true, true))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelSender(List.of(adapter("", true, false))));
    }
}
