package dev.semeniuk.cashmatch.fx;

import dev.semeniuk.cashmatch.money.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;

/**
 * Reference rates quoted ECB-style: units of a currency per 1 EUR, one fixing per business day.
 * Conversions use the latest fixing on or before the value date, so weekend payments use
 * Friday's rate exactly as a bank would.
 */
public interface FxRates {

    MathContext PRECISION = MathContext.DECIMAL64;

    /** Units of {@code currency} per 1 EUR on the latest fixing at or before {@code date}. */
    Optional<BigDecimal> perEur(Currency currency, LocalDate date);

    default Optional<BigDecimal> rate(Currency from, Currency to, LocalDate date) {
        if (from.equals(to)) {
            return Optional.of(BigDecimal.ONE);
        }
        return perEur(from, date).flatMap(f -> perEur(to, date).map(t -> t.divide(f, PRECISION)));
    }

    default Money convert(Money money, Currency to, LocalDate date) {
        var rate = rate(money.currency(), to, date)
                .orElseThrow(() -> new MissingFxRateException(money.currency(), to, date));
        return Money.of(money.amount().multiply(rate, PRECISION), to);
    }
}
