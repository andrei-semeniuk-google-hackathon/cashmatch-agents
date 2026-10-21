package dev.semeniuk.cashmatch.fx;

import java.time.LocalDate;
import java.util.Currency;

public class MissingFxRateException extends IllegalStateException {
    public MissingFxRateException(Currency from, Currency to, LocalDate date) {
        super("no FX fixing for " + from + "/" + to + " on or before " + date);
    }
}
