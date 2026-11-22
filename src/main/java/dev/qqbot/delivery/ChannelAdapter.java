package dev.qqbot.delivery;

/** A channel extension registered as a Spring bean by the application or a plugin. */
public interface ChannelAdapter {
    String channel();

    boolean enabled();

    default boolean supportsPrivateMessages() {
        return false;
    }

    MessageTransport.Delivery send(MessageTransport.Message message);
}
