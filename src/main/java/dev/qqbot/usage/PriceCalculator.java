package dev.qqbot.usage;

import dev.qqbot.MemoryProperties;
import dev.qqbot.model.ModelUsage;
import dev.qqbot.model.UsageStatus;
import java.math.*;

public final class PriceCalculator {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private PriceCalculator() {}

    public record Prices(
            String currency,
            String priceVersion,
            BigDecimal inputPrice,
            BigDecimal outputPrice,
            BigDecimal cacheReadPrice,
            BigDecimal cacheWritePrice) {
        public static Prices from(MemoryProperties settings) {
            return new Prices(
                    settings.currency(),
                    settings.priceVersion(),
                    settings.inputPrice(),
                    settings.outputPrice(),
                    settings.cacheReadPrice(),
                    settings.cacheWritePrice());
        }
    }

    public static BigDecimal reported(ModelUsage usage, Prices prices) {
        if (usage.status() != UsageStatus.REPORTED
                || usage.inputTokens() == null
                || usage.outputTokens() == null
                || usage.cacheReadTokens() == null
                || usage.cacheWriteTokens() == null) {
            return null;
        }
        Long[] tokens = {
            usage.inputTokens(),
            usage.outputTokens(),
            usage.cacheReadTokens(),
            usage.cacheWriteTokens()
        };
        BigDecimal[] rates = {
            prices.inputPrice(),
            prices.outputPrice(),
            prices.cacheReadPrice(),
            prices.cacheWritePrice()
        };
        BigDecimal amount = BigDecimal.ZERO;
        for (int index = 0; index < tokens.length; index++) {
            if (tokens[index] == 0) {
                continue;
            }
            if (rates[index] == null) {
                return null;
            }
            amount = amount.add(BigDecimal.valueOf(tokens[index]).multiply(rates[index]));
        }
        return amount.divide(MILLION, 8, RoundingMode.HALF_UP);
    }

    public static BigDecimal estimated(long input, long output, Prices prices) {
        if (prices.inputPrice() == null || prices.outputPrice() == null) {
            return null;
        }
        return BigDecimal.valueOf(input)
                .multiply(prices.inputPrice())
                .add(BigDecimal.valueOf(output).multiply(prices.outputPrice()))
                .divide(MILLION, 8, RoundingMode.HALF_UP);
    }
}
