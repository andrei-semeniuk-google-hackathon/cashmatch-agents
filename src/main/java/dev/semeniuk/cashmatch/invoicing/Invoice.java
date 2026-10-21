package dev.semeniuk.cashmatch.invoicing;

import dev.semeniuk.cashmatch.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

@Table("invoices")
public record Invoice(
        @Id UUID id,
        String number,
        UUID customerId,
        String currency,
        BigDecimal amount,
        BigDecimal openAmount,
        LocalDate issueDate,
        LocalDate dueDate,
        Status status,
        @Version Long version) {

    public enum Status { OPEN, PARTIALLY_PAID, PAID }

    public Money total() {
        return Money.of(amount, Currency.getInstance(currency));
    }

    public Money open() {
        return Money.of(openAmount, Currency.getInstance(currency));
    }

    public boolean isOpen() {
        return status != Status.PAID;
    }

    Invoice settle(Money payment) {
        var remaining = open().minus(payment);
        if (remaining.isNegative()) {
            throw new IllegalArgumentException(
                    "allocating " + payment + " to " + number + " exceeds its open " + open());
        }
        var next = remaining.isZero() ? Status.PAID : Status.PARTIALLY_PAID;
        return new Invoice(id, number, customerId, currency, amount, remaining.amount(), issueDate,
                dueDate, next, version);
    }
}
