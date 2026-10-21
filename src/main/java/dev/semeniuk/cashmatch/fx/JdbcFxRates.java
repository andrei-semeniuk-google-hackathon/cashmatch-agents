package dev.semeniuk.cashmatch.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcFxRates implements FxRates {

    private static final Currency EUR = Currency.getInstance("EUR");
    /** Covers weekends and holiday bridges; anything older is a data problem, not a rate. */
    private static final int MAX_STALENESS_DAYS = 5;

    private final JdbcClient jdbc;
    /**
     * Published fixings never change, so a found rate is cached for good. A missing one is not
     * cached: today's fixing may simply not have been loaded yet.
     */
    private final Map<Key, BigDecimal> cache = new ConcurrentHashMap<>();

    private record Key(Currency currency, LocalDate date) {}

    JdbcFxRates(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BigDecimal> perEur(Currency currency, LocalDate date) {
        if (currency.equals(EUR)) {
            return Optional.of(BigDecimal.ONE);
        }
        var key = new Key(currency, date);
        var cached = cache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        var found = load(currency, date);
        found.ifPresent(rate -> cache.put(key, rate));
        return found;
    }

    private Optional<BigDecimal> load(Currency currency, LocalDate date) {
        return jdbc.sql("""
                        SELECT per_eur FROM fx_rates
                        WHERE currency = :currency AND rate_date <= :date AND rate_date > :stale
                        ORDER BY rate_date DESC LIMIT 1
                        """)
                .param("currency", currency.getCurrencyCode())
                .param("date", date)
                .param("stale", date.minusDays(MAX_STALENESS_DAYS))
                .query(BigDecimal.class)
                .optional();
    }
}
