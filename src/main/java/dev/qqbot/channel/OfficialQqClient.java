/**
 * [INPUT]: 接收官方 QQ 配置和有类型的投递请求，依赖令牌缓存及 HTTP 边界。
 * [OUTPUT]: 发现长连接地址，发送群聊和私聊消息，返回明确的回执状态。
 * [POS]: 官方 QQ 平台适配器，区分凭据错误、发送失败和不可确认的结果。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.channel;

import static dev.qqbot.delivery.MessageTransport.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.BotProperties;
import dev.qqbot.channel.OfficialQqHttp.Outgoing;
import dev.qqbot.channel.OfficialQqHttp.Receipt;
import dev.qqbot.channel.OfficialQqHttp.TokenReply;
import dev.qqbot.delivery.ChannelAdapter;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public final class OfficialQqClient implements ChannelAdapter {
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;
    private static final int RATE_LIMITED = 429;
    private static final long DUPLICATE_CODE = 40_054_005;
    private static final long PASSIVE_WINDOW_SECONDS = 300;
    private static final int MAX_REPLY_SEQUENCE = 5;
    private static final String API_AUTH_FAILED = "QQ_API_AUTH_FAILED";
    private final boolean isEnabled;
    private final boolean allowsActiveMessages;
    private final OfficialQqHttp http;
    private final TokenCache tokens;

    /** 创建共享 HTTP 连接和令牌缓存的官方 QQ 适配器。 */
    public OfficialQqClient(BotProperties properties, ObjectMapper json) {
        var config = properties.qq().official();
        this.isEnabled = properties.qq().enabled();
        this.allowsActiveMessages = config.allowActiveMessages();
        this.http = new OfficialQqHttp(config, json);
        this.tokens = new TokenCache(http);
    }

    @Override
    public String channel() {
        return "QQ";
    }

    @Override
    public boolean enabled() {
        return isEnabled;
    }

    @Override
    public boolean supportsPrivateMessages() {
        return true;
    }

    /** 向消息指定的群或用户投递，返回平台确认状态。 */
    @Override
    public Delivery send(Message message) {
        Objects.requireNonNull(message, "message must not be null");
        return send(message.target(), message.text(), message.reply(), message.privateChat());
    }

    /** 获取长连接标识令牌。强制刷新时跳过缓存。 */
    public String gatewayToken(boolean force) throws Exception {
        return tokens.accessToken(force);
    }

    /** 发现 WebSocket 地址，认证过期时刷新一次。 */
    public URI gateway() throws Exception {
        var response = http.fetchGateway(tokens.accessToken(false));
        if (response.statusCode() == UNAUTHORIZED) {
            response = http.fetchGateway(tokens.accessToken(true));
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("QQ_GATEWAY_HTTP_" + response.statusCode());
        }
        var reply = http.decodeGateway(response.body());
        if (reply.code() != 0) {
            throw new IllegalStateException("QQ_GATEWAY_API_" + reply.code());
        }
        return requireGatewayUri(reply.url());
    }

    private static URI requireGatewayUri(String address) {
        URI uri = URI.create(address);
        String scheme = uri.getScheme();
        if (!("wss".equals(scheme) || "ws".equals(scheme)) || uri.getHost() == null) {
            throw new IllegalStateException("QQ_GATEWAY_INVALID_URL");
        }
        return uri;
    }

    /** 向群投递文本；主动消息须在部署配置中授权。 */
    public Delivery send(String groupId, String text, Reply reply) {
        return send(groupId, text, reply, false);
    }

    /** 向用户投递文本；被动回复须携带未失效的原消息。 */
    public Delivery sendPrivate(String userId, String text, Reply reply) {
        return send(userId, text, reply, true);
    }

    private Delivery send(String target, String text, Reply reply, boolean isPrivateChat) {
        var problem = validateDelivery(target, reply, allowsActiveMessages, Instant.now());
        if (problem.isPresent()) {
            return failed(problem.orElseThrow());
        }
        try {
            Outgoing message = http.prepare(target, text, reply, isPrivateChat);
            return sendAuthorized(message);
        } catch (ConnectException | HttpConnectTimeoutException failure) {
            return new Delivery(State.RETRYABLE, null, "QQ_CONNECT_FAILED");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unknown("QQ_SEND_INTERRUPTED");
        } catch (IOException | RuntimeException failure) {
            return unknown("QQ_SEND_RESULT_UNKNOWN");
        }
    }

    static Optional<String> validateDelivery(
            String target, Reply reply, boolean allowsActiveMessages, Instant now) {
        if (target == null || target.isBlank()) {
            return Optional.of("QQ_TARGET_MISSING");
        }
        if (reply == null) {
            return allowsActiveMessages
                    ? Optional.empty()
                    : Optional.of("QQ_ACTIVE_MESSAGES_DISABLED");
        }
        if (reply.messageId() == null
                || reply.messageId().isBlank()
                || reply.occurredAt() == null) {
            return Optional.of("QQ_REPLY_CONTEXT_MISSING");
        }
        if (!now.isBefore(reply.occurredAt().plusSeconds(PASSIVE_WINDOW_SECONDS))) {
            return Optional.of("QQ_REPLY_EXPIRED");
        }
        if (reply.sequence() < 1 || reply.sequence() > MAX_REPLY_SEQUENCE) {
            return Optional.of("QQ_REPLY_LIMIT");
        }
        return Optional.empty();
    }

    private Delivery sendAuthorized(Outgoing message) throws IOException, InterruptedException {
        Authorization authorization = authorize(false);
        if (authorization instanceof Rejected rejected) {
            return rejected.delivery();
        }
        // SIDE EFFECT: 平台受理后无法回滚。只有明确的认证失败才重新发送。
        var response = http.sendMessage(message, ((Authorized) authorization).credential());
        if (response.statusCode() == UNAUTHORIZED) {
            Authorization refreshed = authorize(true);
            if (refreshed instanceof Rejected rejected) {
                return rejected.delivery();
            }
            response = http.sendMessage(message, ((Authorized) refreshed).credential());
        }
        var status = classifyHttp(response.statusCode());
        if (status.isPresent()) {
            return status.orElseThrow();
        }
        return classifyReceipt(response.statusCode(), http.decodeReceipt(response.body()));
    }

    private Authorization authorize(boolean force) {
        try {
            return new Authorized(tokens.accessToken(force));
        } catch (TokenCache.Failure failure) {
            State state = failure.isRetryable() ? State.RETRYABLE : State.FAILED;
            return new Rejected(new Delivery(state, null, failure.getMessage()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Rejected(
                    new Delivery(
                            State.RETRYABLE,
                            null,
                            force ? TokenCache.UNAVAILABLE : "QQ_TOKEN_INTERRUPTED"));
        } catch (IOException | RuntimeException failure) {
            return new Rejected(new Delivery(State.RETRYABLE, null, TokenCache.UNAVAILABLE));
        }
    }

    private static Optional<Delivery> classifyHttp(int status) {
        if (status == RATE_LIMITED) {
            return Optional.of(new Delivery(State.RETRYABLE, null, "QQ_RATE_LIMITED"));
        }
        if (status == UNAUTHORIZED || status == FORBIDDEN) {
            return Optional.of(failed(API_AUTH_FAILED));
        }
        if (status >= 500) {
            return Optional.of(unknown("QQ_HTTP_RESULT_UNKNOWN"));
        }
        return Optional.empty();
    }

    static Delivery classifyReceipt(int status, Receipt receipt) {
        if (receipt.code() == DUPLICATE_CODE) {
            return unknown("QQ_DUPLICATE_RECEIPT_UNKNOWN");
        }
        if (receipt.code() != 0) {
            return failed("QQ_API_" + receipt.code());
        }
        if (status < 200 || status >= 300) {
            return failed("QQ_API_REJECTED");
        }
        return receipt.id().isBlank()
                ? unknown("QQ_MISSING_RECEIPT")
                : new Delivery(State.ACCEPTED, receipt.id(), null);
    }

    private static Delivery failed(String code) {
        return new Delivery(State.FAILED, null, code);
    }

    private static Delivery unknown(String code) {
        return new Delivery(State.UNKNOWN, null, code);
    }

    private sealed interface Authorization permits Authorized, Rejected {}

    private record Authorized(String credential) implements Authorization {}

    private record Rejected(Delivery delivery) implements Authorization {}

    private static final long MAX_REFRESH_MARGIN_SECONDS = 60;

    static Instant refreshAt(Instant now, long validSeconds) {
        long margin = Math.min(MAX_REFRESH_MARGIN_SECONDS, Math.max(1, validSeconds / 10));
        return now.plusSeconds(Math.max(1, validSeconds - margin));
    }

    private static final class TokenCache {
        static final String UNAVAILABLE = "QQ_TOKEN_UNAVAILABLE";
        private static final int RETRYABLE_PLATFORM_CODE = 100001;
        private final OfficialQqHttp http;
        private Cached cached;

        TokenCache(OfficialQqHttp http) {
            this.http = http;
        }

        synchronized String accessToken(boolean force)
                throws IOException, InterruptedException, Failure {
            if (!force && cached != null && Instant.now().isBefore(cached.refreshAt())) {
                return cached.credential();
            }
            TokenReply reply = readToken();
            // SIDE EFFECT: 只缓存格式和期限均有效的凭据。
            cached = new Cached(reply.credential(), refreshAt(Instant.now(), reply.validSeconds()));
            return cached.credential();
        }

        private TokenReply readToken() throws IOException, InterruptedException, Failure {
            HttpResponse<String> response = http.exchangeToken();
            verifyStatus(response.statusCode());
            TokenReply reply = http.decodeToken(response.body());
            if (reply.code() != 0) {
                throw new Failure(
                        "QQ_TOKEN_" + reply.code(), reply.code() == RETRYABLE_PLATFORM_CODE);
            }
            if (reply.credential().isBlank() || reply.validSeconds() < 1) {
                throw new Failure("QQ_TOKEN_INVALID_RESPONSE", false);
            }
            return reply;
        }

        private static void verifyStatus(int status) throws Failure {
            if (status == 429 || status >= 500) {
                throw new Failure(TokenCache.UNAVAILABLE, true);
            }
            if (status != 200) {
                throw new Failure("QQ_TOKEN_AUTH_FAILED", false);
            }
        }

        private record Cached(String credential, Instant refreshAt) {}

        static final class Failure extends Exception {
            private final boolean isRetryable;

            Failure(String code, boolean isRetryable) {
                super(code);
                this.isRetryable = isRetryable;
            }

            boolean isRetryable() {
                return isRetryable;
            }
        }
    }
}
