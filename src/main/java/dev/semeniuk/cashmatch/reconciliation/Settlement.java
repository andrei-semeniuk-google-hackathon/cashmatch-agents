package dev.semeniuk.cashmatch.reconciliation;

import dev.semeniuk.cashmatch.money.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/**
 * How one payment settles a set of invoices, entirely in the invoices' currency.
 *
 * <p>Balanced by construction: {@code received + bankCharges + fxDifference = allocated + unapplied}.
 * The FX difference is the plug, which is why its size is the best signal that a match is wrong.
 */
public record Settlement(
        UUID customerId,
        Currency currency,
        BigDecimal rate,
        Money received,
        List<Allocation> allocations,
        Money bankCharges,
        Money fxDifference,
        Money unapplied,
        Kind kind) {

    public record Allocation(UUID invoiceId, String invoiceNumber, Money amount) {}

    public enum Kind {
        /** Every selected invoice is fully paid; differences are within tolerance. */
        FULL,
        /** Fully paid after treating a short payment as correspondent-bank fees. */
        FULL_NET_OF_CHARGES,
        /** Paid less than owed: oldest due invoices first, last one partially. */
        PARTIAL,
        /** Paid more than owed: the rest stays on the customer's account as unapplied cash. */
        OVERPAYMENT;

        public boolean settlesInFull() {
            return this == FULL || this == FULL_NET_OF_CHARGES;
        }
    }

    public Money allocated() {
        return allocations.stream().map(Allocation::amount).reduce(Money.zero(currency), Money::plus);
    }

    public List<String> invoiceNumbers() {
        return allocations.stream().map(Allocation::invoiceNumber).toList();
    }
}
