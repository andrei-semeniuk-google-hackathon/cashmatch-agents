package dev.semeniuk.cashmatch.reconciliation;

import dev.semeniuk.cashmatch.banking.BankTransaction;
import dev.semeniuk.cashmatch.invoicing.Invoice;
import dev.semeniuk.cashmatch.invoicing.Receivables;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Cheap, explainable rules that handle the bulk of payments without a model.
 * Only what these rules cannot place is handed to the agent.
 */
@Component
class RuleBasedMatcher {

    static final int MAX_OPEN_INVOICES_FOR_COMBINATIONS = 15;
    static final int MAX_INVOICES_PER_PAYMENT = 4;

    private final Receivables receivables;
    private final SettlementCalculator calculator;

    RuleBasedMatcher(Receivables receivables, SettlementCalculator calculator) {
        this.receivables = receivables;
        this.calculator = calculator;
    }

    record RuleMatch(String rule, Settlement settlement) {}

    Optional<RuleMatch> match(BankTransaction payment) {
        return byReference(payment).or(() -> byAccountAndAmount(payment));
    }

    /** Rule 1: the remittance text names open invoices of one customer. Partial payments allowed. */
    private Optional<RuleMatch> byReference(BankTransaction payment) {
        var numbers = Receivables.invoiceNumbersIn(payment.remittanceInfo());
        if (numbers.isEmpty()) {
            return Optional.empty();
        }
        var invoices = receivables.invoices(numbers);
        if (invoices.size() != numbers.size()) {
            return Optional.empty();
        }
        var payer = receivables.customerByIban(payment.debtorIban());
        if (payer.isPresent() && invoices.stream().anyMatch(i -> !i.customerId().equals(payer.get().id()))) {
            return Optional.empty(); // a third party paying someone else's invoice needs a human or the agent
        }
        return calculator.settleOrEmpty(payment, invoices).map(s -> new RuleMatch("invoice-reference", s));
    }

    /**
     * Rule 2: a known payer account and exactly one combination of its open invoices that the
     * amount settles in full. Two equally good combinations means we do not know: no match.
     */
    private Optional<RuleMatch> byAccountAndAmount(BankTransaction payment) {
        var payer = receivables.customerByIban(payment.debtorIban());
        if (payer.isEmpty()) {
            return Optional.empty();
        }
        var open = receivables.openInvoices(payer.get().id());
        if (open.isEmpty() || open.size() > MAX_OPEN_INVOICES_FOR_COMBINATIONS) {
            return Optional.empty();
        }
        var fits = new ArrayList<Settlement>();
        for (var combination : combinations(open, MAX_INVOICES_PER_PAYMENT)) {
            calculator.settleOrEmpty(payment, combination)
                    .filter(s -> s.kind().settlesInFull())
                    .ifPresent(fits::add);
            if (fits.size() > 1) {
                return Optional.empty();
            }
        }
        return fits.stream().findFirst().map(s -> new RuleMatch("account-and-amount", s));
    }

    static List<List<Invoice>> combinations(List<Invoice> invoices, int maxSize) {
        var result = new ArrayList<List<Invoice>>();
        collect(invoices, 0, maxSize, new ArrayList<>(), result);
        return result;
    }

    private static void collect(List<Invoice> all, int start, int maxSize, List<Invoice> current,
            List<List<Invoice>> out) {
        if (!current.isEmpty()) {
            out.add(List.copyOf(current));
        }
        if (current.size() == maxSize) {
            return;
        }
        for (int i = start; i < all.size(); i++) {
            current.add(all.get(i));
            collect(all, i + 1, maxSize, current, out);
            current.removeLast();
        }
    }
}
