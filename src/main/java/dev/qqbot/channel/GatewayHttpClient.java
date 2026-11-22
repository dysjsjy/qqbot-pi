package dev.qqbot.channel;

import static dev.qqbot.delivery.MessageTransport.*;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class GatewayHttpClient {
    private final ObjectMapper json;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public GatewayHttpClient(ObjectMapper json) {
        this.json = json;
    }

    public Delivery post(
            String baseUrl,
            String endpoint,
            String token,
            Object payload,
            Function<JsonNode, Delivery> receipt) {
        try {
            var request =
                    HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + endpoint))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(payload)))
                            .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                return new Delivery(State.RETRYABLE, null, "RATE_LIMITED");
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return new Delivery(State.FAILED, null, "GATEWAY_AUTH_FAILED");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new Delivery(State.UNKNOWN, null, "HTTP_RESULT_UNKNOWN");
            }
            return receipt.apply(json.readTree(response.body()));
        } catch (ConnectException | HttpConnectTimeoutException failure) {
            return new Delivery(State.RETRYABLE, null, "CONNECT_FAILED");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new Delivery(State.UNKNOWN, null, "SEND_INTERRUPTED");
        } catch (Exception failure) {
            return new Delivery(State.UNKNOWN, null, "SEND_RESULT_UNKNOWN");
        }
    }
}
