package dev.qqbot.usage;

import dev.qqbot.BotProperties;
import dev.qqbot.MemoryProperties;
import dev.qqbot.model.*;
import dev.qqbot.persistence.Db;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ModelCallStore {
    private final Db db;
    private final BotProperties properties;
    private final MemoryProperties memory;
    private final Clock clock;

    public ModelCallStore(Db db, BotProperties properties, MemoryProperties memory, Clock clock) {
        this.db = db;
        this.properties = properties;
        this.memory = memory;
        this.clock = clock;
    }

    public void begin(GenerationRequest request) {
        var prices = PriceCalculator.Prices.from(memory);
        db.exec(
                """
            insert into model_call(id,run_id,group_id,epoch,purpose,provider,model,estimated_input_tokens,max_output_tokens,currency,price_version,price_snapshot,created_at)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
                request.callId(),
                request.runId(),
                request.groupId(),
                request.epoch(),
                request.purpose().name(),
                properties.pi().provider(),
                properties.pi().model(),
                TokenEstimator.estimateInput(request.systemPrompt(), request.userPrompt()),
                request.maxOutputTokens(),
                prices.currency(),
                prices.priceVersion(),
                db.json(prices),
                clock.instant());
    }

    public void dispatched(UUID callId) {
        db.tx(
                () -> {
                    var row = required(callId);
                    if (!"RUNNING".equals(row.get("state"))) {
                        throw new IllegalStateException("MODEL_CALL_FINISHED");
                    }
                    db.exec("update model_call set dispatched=true where id=?", callId);
                    settle(callId, usage(row), true, row);
                    return null;
                });
    }

    public void usage(UUID callId, ModelUsage value) {
        db.tx(
                () -> {
                    var row = required(callId);
                    if ("REPORTED".equals(row.get("usage_status"))
                            && value.status() != UsageStatus.REPORTED) {
                        return null;
                    }
                    db.exec(
                            "update model_call set usage_status=?,input_tokens=?,output_tokens=?,cache_read_tokens=?,cache_write_tokens=?,reasoning_tokens=? where id=?",
                            value.status().name(),
                            value.inputTokens(),
                            value.outputTokens(),
                            value.cacheReadTokens(),
                            value.cacheWriteTokens(),
                            value.reasoningTokens(),
                            callId);
                    settle(callId, value, Boolean.TRUE.equals(row.get("dispatched")), row);
                    return null;
                });
    }

    public void finish(UUID callId, String state, String errorCode) {
        db.tx(
                () -> {
                    var row = required(callId);
                    settle(callId, usage(row), Boolean.TRUE.equals(row.get("dispatched")), row);
                    db.exec(
                            "update model_call set state=?,error_code=?,finished_at=? where id=?",
                            state,
                            errorCode,
                            clock.instant(),
                            callId);
                    return null;
                });
    }

    public void recover() {
        for (var row : db.list("select id,dispatched from model_call where state='RUNNING'")) {
            finish(
                    (UUID) row.get("id"),
                    Boolean.TRUE.equals(row.get("dispatched")) ? "UNKNOWN" : "FAILED",
                    "SERVICE_RESTART");
        }
    }

    public int expire() {
        return db.exec(
                "delete from model_call where created_at<?",
                clock.instant().minus(Duration.ofDays(90)));
    }

    private Map<String, Object> required(UUID callId) {
        var row = db.one("select * from model_call where id=?", callId);
        if (row == null) {
            throw new IllegalStateException("MODEL_CALL_MISSING");
        }
        return row;
    }

    private void settle(
            UUID callId, ModelUsage usage, boolean dispatched, Map<String, Object> row) {
        var prices = db.parse((String) row.get("price_snapshot"), PriceCalculator.Prices.class);
        BigDecimal reported =
                dispatched ? PriceCalculator.reported(usage, prices) : BigDecimal.ZERO.setScale(8);
        BigDecimal estimated =
                dispatched && usage.status() != UsageStatus.REPORTED
                        ? PriceCalculator.estimated(
                                ((Number) row.get("estimated_input_tokens")).longValue(),
                                ((Number) row.get("max_output_tokens")).longValue(),
                                prices)
                        : null;
        db.exec(
                "update model_call set reported_cost=?,estimated_cost=? where id=?",
                money(reported),
                money(estimated),
                callId);
    }

    private Object money(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }

    private ModelUsage usage(Map<String, Object> row) {
        return new ModelUsage(
                count(row, "input_tokens"),
                count(row, "output_tokens"),
                count(row, "cache_read_tokens"),
                count(row, "cache_write_tokens"),
                count(row, "reasoning_tokens"),
                UsageStatus.valueOf((String) row.get("usage_status")));
    }

    private Long count(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : ((Number) row.get(key)).longValue();
    }
}
