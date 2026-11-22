package dev.qqbot.channel;

import dev.qqbot.delivery.ChannelAdapter;
import dev.qqbot.delivery.MessageTransport;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ChannelSender implements MessageTransport {
    private final Map<String, ChannelAdapter> adapters;

    public ChannelSender(List<ChannelAdapter> adapters) {
        var registered = new HashMap<String, ChannelAdapter>();
        for (var adapter : adapters) {
            if (adapter.channel() == null || adapter.channel().isBlank()) {
                throw new IllegalArgumentException("Channel adapter requires a name");
            }
            if (registered.putIfAbsent(adapter.channel(), adapter) != null) {
                throw new IllegalStateException("Duplicate channel adapter: " + adapter.channel());
            }
        }
        this.adapters = Map.copyOf(registered);
    }

    @Override
    public Delivery send(Message message) {
        var adapter = adapters.get(message.channel());
        if (adapter == null) {
            return new Delivery(State.FAILED, null, "CHANNEL_UNSUPPORTED");
        }
        if (!adapter.enabled()) {
            return new Delivery(State.FAILED, null, "GATEWAY_DISABLED");
        }
        if (message.privateChat() && !adapter.supportsPrivateMessages()) {
            return new Delivery(State.FAILED, null, "PRIVATE_CHANNEL_UNSUPPORTED");
        }
        if (message.target() == null || message.target().isBlank()) {
            return new Delivery(State.FAILED, null, "TARGET_MISSING");
        }
        return adapter.send(message);
    }
}
