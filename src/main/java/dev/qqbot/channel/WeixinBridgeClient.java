package dev.qqbot.channel;

import static dev.qqbot.delivery.MessageTransport.*;

import dev.qqbot.BotProperties;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class WeixinBridgeClient implements dev.qqbot.delivery.ChannelAdapter {
    private final BotProperties properties;
    private final GatewayHttpClient http;

    public WeixinBridgeClient(BotProperties properties, GatewayHttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    @Override
    public String channel() {
        return "WEIXIN";
    }

    @Override
    public boolean enabled() {
        return properties.weixin().enabled();
    }

    public Delivery send(Message message) {
        var gateway = properties.weixin();
        var payload =
                Map.of(
                        "groupId",
                        message.target(),
                        "text",
                        message.text(),
                        "requestId",
                        message.requestId());
        return http.post(
                gateway.baseUrl(),
                "/api/bot/send",
                gateway.token(),
                payload,
                body ->
                        switch (body.path("status").asText()) {
                            case "accepted" ->
                                    body.path("messageId").asText("").isBlank()
                                            ? new Delivery(State.UNKNOWN, null, "MISSING_RECEIPT")
                                            : new Delivery(
                                                    State.ACCEPTED,
                                                    body.path("messageId").asText(),
                                                    null);
                            case "rejected" -> new Delivery(State.FAILED, null, "BRIDGE_REJECTED");
                            case "retryable" -> new Delivery(State.RETRYABLE, null, "BRIDGE_BUSY");
                            default -> new Delivery(State.UNKNOWN, null, "BRIDGE_RESULT_UNKNOWN");
                        });
    }
}
