package dev.qqbot.delivery;

import java.time.Instant;

public interface MessageTransport {
    enum State {
        ACCEPTED,
        RETRYABLE,
        FAILED,
        UNKNOWN
    }

    record Delivery(State state, String platformId, String code) {}

    record Reply(String messageId, Instant occurredAt, int sequence) {}

    record Message(
            String channel,
            String target,
            boolean privateChat,
            String text,
            String requestId,
            Reply reply) {}

    Delivery send(Message message);
}
