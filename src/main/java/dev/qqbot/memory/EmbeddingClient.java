package dev.qqbot.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.qqbot.BotProperties;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingClient {
    private final BotProperties.Embedding config;
    private final ObjectMapper json;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public EmbeddingClient(BotProperties props, ObjectMapper json) {
        this.config = props.embedding();
        this.json = json;
    }

    public List<float[]> embed(List<String> texts) {
        if (config.url().isBlank() || config.model().isBlank()) {
            throw new IllegalStateException("EMBEDDING_NOT_CONFIGURED");
        }
        try {
            var body = json.writeValueAsBytes(Map.of("model", config.model(), "input", texts));
            var builder =
                    HttpRequest.newBuilder(URI.create(config.url()))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (!config.apiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + config.apiKey());
            }
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("EMBEDDING_HTTP_" + response.statusCode());
            }
            JsonNode rows = json.readTree(response.body()).path("data");
            if (rows.size() != texts.size()) {
                throw new IllegalStateException("EMBEDDING_COUNT_MISMATCH");
            }
            List<float[]> result = new ArrayList<>(Collections.nCopies(texts.size(), null));
            for (JsonNode row : rows) {
                int index = row.path("index").asInt(-1);
                if (index < 0 || index >= result.size() || result.get(index) != null) {
                    throw new IllegalStateException("EMBEDDING_INVALID_INDEX");
                }
                JsonNode vector = row.path("embedding");
                if (vector.size() != config.dimensions()) {
                    throw new IllegalStateException("EMBEDDING_DIMENSION_MISMATCH");
                }
                float[] values = new float[vector.size()];
                double norm = 0;
                for (int i = 0; i < values.length; i++) {
                    if (!vector.get(i).isNumber()) {
                        throw new IllegalStateException("EMBEDDING_INVALID_VALUE");
                    }
                    values[i] = (float) vector.get(i).asDouble();
                    if (!Float.isFinite(values[i])) {
                        throw new IllegalStateException("EMBEDDING_INVALID_VALUE");
                    }
                    norm += values[i] * values[i];
                }
                if (norm == 0) {
                    throw new IllegalStateException("EMBEDDING_ZERO_VECTOR");
                }
                result.set(index, values);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("EMBEDDING_INTERRUPTED", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("EMBEDDING_FAILED", e);
        }
    }

    public static String vector(float[] values) {
        StringJoiner join = new StringJoiner(",", "[", "]");
        for (float value : values) {
            join.add(Float.toString(value));
        }
        return join.toString();
    }
}
