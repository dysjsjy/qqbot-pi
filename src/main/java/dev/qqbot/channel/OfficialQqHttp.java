/**
 * [INPUT]: 依赖 QQ 官方配置、请求凭据和 Jackson。
 * [OUTPUT]: 发送令牌和官方 API 请求，将响应解码为协议数据。
 * [POS]: QQ 官方 HTTP 边界，不记录凭据或平台正文。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.channel;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.BotProperties.OfficialQq;
import dev.qqbot.delivery.MessageTransport.Reply;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class OfficialQqHttp {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String APPLICATION_JSON = "application/json";
    private static final String CODE = "code";
    private final OfficialQq config;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    OfficialQqHttp(OfficialQq config, ObjectMapper json) {
        this.config = config;
        this.json = json;
    }

    HttpResponse<String> exchangeToken() throws IOException, InterruptedException {
        String body = json.writeValueAsString(new TokenRequest(config.appId(), config.appSecret()));
        var builder = timedRequest(URI.create(config.tokenUrl()));
        return postJson(builder, body);
    }

    HttpResponse<String> fetchGateway(String credential) throws IOException, InterruptedException {
        var builder = authorizedRequest(endpoint("/gateway"), credential);
        return send(builder.GET().build());
    }

    Outgoing prepare(String target, String text, Reply reply, boolean isPrivateChat)
            throws JsonProcessingException {
        String scope = isPrivateChat ? "/v2/users/" : "/v2/groups/";
        String encoded = URLEncoder.encode(target, StandardCharsets.UTF_8).replace("+", "%20");
        var payload =
                new MessageBody(
                        0,
                        text,
                        reply == null ? null : reply.messageId(),
                        reply == null ? null : reply.sequence());
        return new Outgoing(
                endpoint(scope + encoded + "/messages"), json.writeValueAsString(payload));
    }

    HttpResponse<String> sendMessage(Outgoing message, String credential)
            throws IOException, InterruptedException {
        return postJson(authorizedRequest(message.uri(), credential), message.body());
    }

    private URI endpoint(String path) {
        return URI.create(config.apiBaseUrl().replaceAll("/+$", "") + path);
    }

    private HttpRequest.Builder authorizedRequest(URI uri, String credential) {
        return timedRequest(uri)
                .header("Authorization", "QQBot " + credential)
                .header("X-Union-Appid", config.appId());
    }

    private static HttpRequest.Builder timedRequest(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);
    }

    private HttpResponse<String> postJson(HttpRequest.Builder builder, String body)
            throws IOException, InterruptedException {
        builder.header("Content-Type", APPLICATION_JSON);
        builder.POST(HttpRequest.BodyPublishers.ofString(body));
        return send(builder.build());
    }

    private HttpResponse<String> send(HttpRequest request)
            throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    TokenReply decodeToken(String payload) throws JsonProcessingException {
        var object = json.readTree(payload);
        return new TokenReply(
                object.path(CODE).asLong(0),
                object.path("access_token").asText(""),
                object.path("expires_in").asLong(0));
    }

    GatewayReply decodeGateway(String payload) throws JsonProcessingException {
        var object = json.readTree(payload);
        return new GatewayReply(object.path(CODE).asLong(0), object.path("url").asText(""));
    }

    Receipt decodeReceipt(String payload) throws JsonProcessingException {
        var object = json.readTree(payload);
        return new Receipt(object.path(CODE).asLong(0), object.path("id").asText(""));
    }

    record TokenReply(long code, String credential, long validSeconds) {}

    record GatewayReply(long code, String url) {}

    record Receipt(long code, String id) {}

    record Outgoing(URI uri, String body) {}

    private record TokenRequest(String appId, String clientSecret) {}

    private record MessageBody(
            @JsonProperty("msg_type") int type,
            String content,
            @JsonProperty("msg_id") @JsonInclude(JsonInclude.Include.NON_NULL) String replyId,
            @JsonProperty("msg_seq") @JsonInclude(JsonInclude.Include.NON_NULL)
                    Integer replySequence) {}
}
