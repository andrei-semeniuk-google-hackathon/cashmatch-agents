package dev.semeniuk.cashmatch.reconciliation;

import dev.semeniuk.cashmatch.banking.BankTransaction;
import dev.semeniuk.cashmatch.fx.FxRates;
import dev.semeniuk.cashmatch.invoicing.Invoice;
import dev.semeniuk.cashmatch.money.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Deterministic money math for a proposed match. Whoever proposes which invoices a payment
 * belongs to — a rule or an agent — the amounts are always computed here.
 */
@Component
public class SettlementCalculator {

    private static final Currency EUR = Currency.getInstance("EUR");

    private final FxRates fx;
    private final Tolerances tolerances;

    public SettlementCalculator(FxRates fx, Tolerances tolerances) {
        this.fx = fx;
        this.tolerances = tolerances;
    }

    public sealed interface Result {
        record Settled(Settlement settlement) implements Result {}

        record Rejected(String reason) implements Result {}
    }

    public Result settle(BankTransaction payment, List<Invoice> selected) {
        if (selected.isEmpty()) {
            return new Result.Rejected("no invoices selected");
        }
        if (selected.stream().anyMatch(i -> !i.isOpen())) {
            return new Result.Rejected("selection contains an invoice that is already paid");
        }
        if (selected.stream().map(Invoice::customerId).distinct().count() > 1) {
            return new Result.Rejected("invoices belong to different customers");
        }
        if (selected.stream().map(Invoice::currency).distinct().count() > 1) {
            return new Result.Rejected("invoices are in different currencies");
        }

        var invoices = selected.stream()
                .sorted(Comparator.comparing(Invoice::dueDate).thenComparing(Invoice::number))
                .toList();
        var currency = Currency.getInstance(invoices.getFirst().currency());
        var date = payment.valueDate();
        var rate = fx.rate(payment.credited().currency(), currency, date);
        if (rate.isEmpty()) {
            return new Result.Rejected("no FX fixing for " + payment.credited().currency() + "/" + currency);
        }

        var received = fx.convert(payment.credited(), currency, date);
        var reportedCharges = payment.charges().map(c -> fx.convert(c, currency, date))
                .orElse(Money.zero(currency));
        // What the payer meant to pay: their instruction when it is in the invoice currency,
        // otherwise what reached us plus any fees the bank told us about.
        var intended = payment.instructed()
                .filter(i -> i.currency().equals(currency))
                .orElse(received.plus(reportedCharges));

        var owed = invoices.stream().map(Invoice::open).reduce(Money.zero(currency), Money::plus);
        var shortfall = owed.minus(intended);
        // Same-currency payments get no FX slack: a short payment there is a short payment.
        var crossCurrency = !payment.credited().currency().equals(currency);
        var fxTolerance = crossCurrency
                ? owed.times(tolerances.fxRelative()).max(cent(currency))
                : cent(currency);
        var chargeTolerance = fx.convert(Money.of(tolerances.maxBankChargesEur(), EUR), currency, date);

        Settlement.Kind kind;
        var unreportedCharges = Money.zero(currency);
        if (intended.isWithin(fxTolerance, owed)) {
            kind = Settlement.Kind.FULL;
        } else if (shortfall.isPositive()
                && payment.chargeBearer().mayDeductFees()
                && payment.charges().isEmpty()
                && shortfall.compareTo(fxTolerance.plus(chargeTolerance)) <= 0) {
            kind = Settlement.Kind.FULL_NET_OF_CHARGES;
            unreportedCharges = shortfall;
        } else if (shortfall.isPositive()) {
            kind = Settlement.Kind.PARTIAL;
        } else {
            kind = Settlement.Kind.OVERPAYMENT;
        }

        var allocations = new ArrayList<Settlement.Allocation>();
        var available = kind.settlesInFull() ? owed : intended.min(owed);
        for (var invoice : invoices) {
            var amount = invoice.open().min(available);
            if (amount.isPositive()) {
                allocations.add(new Settlement.Allocation(invoice.id(), invoice.number(), amount));
                available = available.minus(amount);
            }
        }
        var allocated = allocations.stream().map(Settlement.Allocation::amount)
                .reduce(Money.zero(currency), Money::plus);
        var unapplied = kind == Settlement.Kind.OVERPAYMENT ? intended.minus(owed) : Money.zero(currency);
        var bankCharges = reportedCharges.plus(unreportedCharges);
        // Plug: whatever is left after cash and fees is the cost (or gain) of conversion.
        var fxDifference = allocated.plus(unapplied).minus(received).minus(bankCharges);

        if (fxDifference.abs().compareTo(fxTolerance) > 0) {
            return new Result.Rejected("FX difference " + fxDifference + " exceeds tolerance "
                    + fxTolerance + "; the payment probably belongs to other invoices");
        }
        return new Result.Settled(new Settlement(invoices.getFirst().customerId(), currency,
                rate.get(), received, List.copyOf(allocations), bankCharges, fxDifference, unapplied, kind));
    }

    public Optional<Settlement> settleOrEmpty(BankTransaction payment, List<Invoice> selected) {
        return settle(payment, selected) instanceof Result.Settled(var s) ? Optional.of(s) : Optional.empty();
    }

    private static Money cent(Currency currency) {
        return Money.of(BigDecimal.ONE.movePointLeft(currency.getDefaultFractionDigits()), currency);
    }
}
