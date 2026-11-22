/**
 * [INPUT]: 依赖模型账目、生成用途与上海业务日时钟。
 * [OUTPUT]: 按群与用途汇总生成调用和费用，含实验画像。
 * [POS]: 用量只读查询；未分发调用不计费。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.usage;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import dev.qqbot.MemoryProperties;
import dev.qqbot.model.Purpose;
import dev.qqbot.persistence.Db;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UsageQueries {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String GENERATION_ONLY = "GENERATION_ONLY";
    private final Db db;
    private final MemoryProperties properties;
    private final Clock clock;

    public UsageQueries(Db db, MemoryProperties properties, Clock clock) {
        this.db = db;
        this.properties = properties;
        this.clock = clock;
    }

    public DailyUsage daily(UUID groupId, LocalDate date) {
        if (db.one("select id from group_config where id=?", groupId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "群配置不存在");
        }
        LocalDate day = date == null ? LocalDate.now(clock.withZone(ZONE)) : date;
        var rows =
                db.list(
                        "select * from model_call where group_id=? and created_at>=? and created_at<? order by created_at,id",
                        groupId,
                        day.atStartOfDay(ZONE).toInstant(),
                        day.plusDays(1).atStartOfDay(ZONE).toInstant());
        var totals = aggregate(rows);
        var purposes = new LinkedHashMap<String, Totals>();
        for (Purpose purpose : Purpose.values()) {
            purposes.put(
                    purpose.name(),
                    aggregate(
                            rows.stream()
                                    .filter(row -> purpose.name().equals(row.get("purpose")))
                                    .toList()));
        }
        return new DailyUsage(totals, day.toString(), ZONE.getId(), purposes);
    }

    private Totals aggregate(List<Map<String, Object>> rows) {
        var currencies = new TreeSet<String>();
        var versions = new TreeSet<String>();
        long dispatched = 0;
        long reported = 0;
        BigDecimal reportedCost = BigDecimal.ZERO.setScale(8);
        BigDecimal estimatedCost = BigDecimal.ZERO.setScale(8);
        boolean missingReported = false;
        boolean missingEstimated = false;
        long inputTokens = 0;
        long outputTokens = 0;
        long cacheReadTokens = 0;
        long cacheWriteTokens = 0;
        for (var row : rows) {
            currencies.add((String) row.get("currency"));
            versions.add((String) row.get("price_version"));
            if (!Boolean.TRUE.equals(row.get("dispatched"))) {
                continue;
            }
            dispatched++;
            inputTokens = Math.addExact(inputTokens, tokens(row, "input_tokens"));
            outputTokens = Math.addExact(outputTokens, tokens(row, "output_tokens"));
            cacheReadTokens = Math.addExact(cacheReadTokens, tokens(row, "cache_read_tokens"));
            cacheWriteTokens = Math.addExact(cacheWriteTokens, tokens(row, "cache_write_tokens"));
            if ("REPORTED".equals(row.get("usage_status"))) {
                reported++;
                if (row.get("reported_cost") == null) {
                    missingReported = true;
                } else {
                    reportedCost =
                            reportedCost.add(new BigDecimal(row.get("reported_cost").toString()));
                }
            } else {
                if (row.get("estimated_cost") == null) {
                    missingEstimated = true;
                } else {
                    estimatedCost =
                            estimatedCost.add(new BigDecimal(row.get("estimated_cost").toString()));
                }
            }
        }
        long unknown = dispatched - reported;
        String currency =
                currencies.size() > 1
                        ? "mixed"
                        : currencies.isEmpty() ? properties.currency() : currencies.first();
        String priceVersion =
                versions.size() > 1
                        ? "mixed"
                        : versions.isEmpty() ? properties.priceVersion() : versions.first();
        BigDecimal knownReported =
                currency.equals("mixed") || missingReported || reported == 0 && dispatched > 0
                        ? null
                        : reportedCost;
        BigDecimal knownEstimated =
                rows.isEmpty()
                        ? BigDecimal.ZERO.setScale(8)
                        : currency.equals("mixed") || missingEstimated || unknown == 0
                                ? null
                                : estimatedCost;
        return new Totals(
                GENERATION_ONLY,
                currency,
                priceVersion,
                rows.size(),
                dispatched,
                reported,
                unknown,
                inputTokens,
                outputTokens,
                cacheReadTokens,
                cacheWriteTokens,
                knownReported,
                knownEstimated);
    }

    private static long tokens(Map<String, Object> row, String column) {
        var count = (Number) row.get(column);
        return count == null ? 0 : count.longValue();
    }

    public record DailyUsage(
            @JsonUnwrapped Totals totals, String date, String zone, Map<String, Totals> byPurpose) {
        public DailyUsage {
            byPurpose = Collections.unmodifiableMap(new LinkedHashMap<>(byPurpose));
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Totals(
            String coverage,
            String currency,
            String priceVersion,
            long calls,
            long dispatchedCalls,
            long reportedCalls,
            long unknownCalls,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            BigDecimal reportedCost,
            BigDecimal estimatedCost) {}
}
