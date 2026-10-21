package dev.semeniuk.cashmatch.reconciliation;

import dev.semeniuk.cashmatch.banking.BankTransaction;
import dev.semeniuk.cashmatch.banking.BankTransaction.ChargeBearer;
import dev.semeniuk.cashmatch.invoicing.Invoice;
import dev.semeniuk.cashmatch.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Test data builders. */
final class Fixtures {

    static final LocalDate VALUE_DATE = LocalDate.of(2026, 10, 20);
    static final UUID CUSTOMER = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private Fixtures() {}

    static BankTransaction payment(String amount, String currency) {
        return payment(Money.of(amount, currency), Optional.empty(), ChargeBearer.DEBT);
    }

    static BankTransaction payment(Money credited, Optional<Money> instructed, ChargeBearer bearer) {
        return new BankTransaction(UUID.randomUUID(), "REF", "E2E", credited, instructed, Optional.empty(),
                bearer, VALUE_DATE, "PAYER", "", "", BankTransaction.Status.RECEIVED);
    }

    static Invoice invoice(String number, String amount, String currency, LocalDate due) {
        return invoice(number, amount, currency, due, CUSTOMER);
    }

    static Invoice invoice(String number, String amount, String currency, LocalDate due, UUID customer) {
        var value = new BigDecimal(amount);
        return new Invoice(UUID.randomUUID(), number, customer, currency, value, value, due.minusDays(30), due,
                Invoice.Status.OPEN, 0L);
    }
}
