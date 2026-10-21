package dev.semeniuk.cashmatch.money;

import java.util.Currency;

public class CurrencyMismatchException extends IllegalArgumentException {
    public CurrencyMismatchException(Currency left, Currency right) {
        super("cannot combine " + left + " with " + right + " without an FX conversion");
    }
}
