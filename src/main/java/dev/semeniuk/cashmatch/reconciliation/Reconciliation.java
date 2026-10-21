package dev.semeniuk.cashmatch.reconciliation;

import dev.semeniuk.cashmatch.banking.BankTransaction;
import dev.semeniuk.cashmatch.banking.PaymentReceived;
import dev.semeniuk.cashmatch.banking.Payments;
import dev.semeniuk.cashmatch.fx.FxRates;
import dev.semeniuk.cashmatch.invoicing.Receivables;
import dev.semeniuk.cashmatch.ledger.Ledger;
import dev.semeniuk.cashmatch.ledger.LedgerLine;
import dev.semeniuk.cashmatch.money.Money;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cash application: rules first, agent second, human last. Nothing is posted that the
 * deterministic settlement calculator and the auto-post policy have not both accepted.
 */
@Service
public class Reconciliation {

    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);
    private static final Currency EUR = Currency.getInstance("EUR");

    private final Payments payments;
    private final Receivables receivables;
    private final RuleBasedMatcher rules;
    private final PaymentInvestigator investigator;
    private final SettlementCalculator calculator;
    private final FxRates fx;
    private final Tolerances tolerances;
    private final Ledger ledger;
    private final ReviewQueue reviews;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;

    Reconciliation(Payments payments, Receivables receivables, RuleBasedMatcher rules,
            ObjectProvider<PaymentInvestigator> investigator, SettlementCalculator calculator, FxRates fx,
            Tolerances tolerances, Ledger ledger, ReviewQueue reviews, ApplicationEventPublisher events,
            TransactionTemplate transactions) {
        this.payments = payments;
        this.receivables = receivables;
        this.rules = rules;
        this.investigator = investigator.getIfAvailable(ManualReviewInvestigator::new);
        this.calculator = calculator;
        this.fx = fx;
        this.tolerances = tolerances;
        this.ledger = ledger;
        this.reviews = reviews;
        this.events = events;
        this.transactions = transactions;
    }

    /**
     * Runs asynchronously after the statement is committed. The Modulith event registry keeps
     * the publication until this completes, so a crash mid-way is retried, not lost.
     */
    @Async
    @TransactionalEventListener
    void on(PaymentReceived event) {
        reconcile(event.transactionId());
    }

    /**
     * Not transactional on purpose: the agent may take seconds, and holding a database
     * connection open across an LLM call is how pools run dry. Only the final write is a
     * transaction, and it re-checks the payment's status before touching anything.
     */
    public PaymentReconciled reconcile(UUID transactionId) {
        var payment = payments.find(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("unknown transaction " + transactionId));
        if (payment.status() != BankTransaction.Status.RECEIVED) {
            // A redelivered event for a payment that is already done: acknowledge, change nothing.
            return new PaymentReconciled(transactionId, PaymentReconciled.Outcome.ALREADY_HANDLED, "none", List.of());
        }

        var ruleMatch = rules.match(payment);
        if (ruleMatch.isPresent()) {
            var match = ruleMatch.get();
            return withinAutoPostLimit(payment)
                    ? write(() -> post(payment, match.settlement(), "rule:" + match.rule()))
                    : write(() -> review(payment, "above auto-post limit (matched by rule " + match.rule() + ")",
                            Optional.empty()));
        }

        var investigation = investigator.investigate(payment);
        log.info("agent investigated {}: {} {} confidence={}", payment.bankReference(),
                investigation.verdict(), investigation.invoiceNumbers(), investigation.confidence());
        return switch (evaluate(payment, investigation)) {
            case AgentDecision.AutoPost(var settlement) -> write(() -> post(payment, settlement, "agent"));
            case AgentDecision.NeedsReview(var reason) ->
                    write(() -> review(payment, reason, Optional.of(investigation)));
        };
    }

    sealed interface AgentDecision {
        record AutoPost(Settlement settlement) implements AgentDecision {}

        record NeedsReview(String reason) implements AgentDecision {}
    }

    /** The auto-post policy for agent proposals. Every branch that is not a clear yes is a human. */
    AgentDecision evaluate(BankTransaction payment, Investigation investigation) {
        if (investigation.verdict() != Investigation.Verdict.MATCH || investigation.invoiceNumbers().isEmpty()) {
            return new AgentDecision.NeedsReview("agent found no match");
        }
        var invoices = receivables.invoices(investigation.invoiceNumbers());
        if (invoices.size() != investigation.invoiceNumbers().size()) {
            return new AgentDecision.NeedsReview("agent proposed invoices that do not exist");
        }
        if (invoices.stream().anyMatch(i -> !i.customerId().equals(investigation.customerId()))) {
            return new AgentDecision.NeedsReview("agent's customer does not own the proposed invoices");
        }
        return switch (calculator.settle(payment, invoices)) {
            case SettlementCalculator.Result.Rejected(var reason) ->
                    new AgentDecision.NeedsReview("agent proposal rejected: " + reason);
            case SettlementCalculator.Result.Settled(var s) when !s.kind().settlesInFull() ->
                    new AgentDecision.NeedsReview("agent proposal does not settle in full (" + s.kind() + ")");
            case SettlementCalculator.Result.Settled(var _)
                    when investigation.confidence() < tolerances.minAgentConfidence() ->
                    new AgentDecision.NeedsReview("agent confidence %.2f below %.2f"
                            .formatted(investigation.confidence(), tolerances.minAgentConfidence()));
            case SettlementCalculator.Result.Settled(var _) when !withinAutoPostLimit(payment) ->
                    new AgentDecision.NeedsReview("above auto-post limit");
            case SettlementCalculator.Result.Settled(var s) -> new AgentDecision.AutoPost(s);
        };
    }

    /** A reviewer approves the agent's proposal, or overrides it with their own invoice list. */
    @Transactional
    public PaymentReconciled approve(UUID reviewId, Optional<List<String>> invoiceNumbers, String reviewer) {
        var task = reviews.find(reviewId)
                .filter(t -> t.status() == ReviewTask.Status.OPEN)
                .orElseThrow(() -> new IllegalArgumentException("no open review " + reviewId));
        var payment = payments.find(task.transactionId()).orElseThrow();
        var numbers = invoiceNumbers.orElse(task.proposedInvoices());
        var settlement = switch (calculator.settle(payment, receivables.invoices(numbers))) {
            case SettlementCalculator.Result.Settled(var s) -> s;
            case SettlementCalculator.Result.Rejected(var reason) -> throw new IllegalArgumentException(reason);
        };
        reviews.close(reviewId, ReviewTask.Status.APPROVED, reviewer);
        return post(payment, settlement, "review:" + reviewer, BankTransaction.Status.IN_REVIEW);
    }

    @Transactional
    public void reject(UUID reviewId, String reviewer) {
        var task = reviews.find(reviewId)
                .filter(t -> t.status() == ReviewTask.Status.OPEN)
                .orElseThrow(() -> new IllegalArgumentException("no open review " + reviewId));
        reviews.close(reviewId, ReviewTask.Status.REJECTED, reviewer);
        payments.transition(task.transactionId(), BankTransaction.Status.IN_REVIEW, BankTransaction.Status.UNMATCHED);
    }

    public List<ReviewTask> openReviews() {
        return reviews.open();
    }

    private PaymentReconciled write(Supplier<PaymentReconciled> work) {
        return transactions.execute(status -> work.get());
    }

    private PaymentReconciled post(BankTransaction payment, Settlement settlement, String source) {
        return post(payment, settlement, source, BankTransaction.Status.RECEIVED);
    }

    private PaymentReconciled post(BankTransaction payment, Settlement settlement, String source,
            BankTransaction.Status expected) {
        if (!payments.transition(payment.id(), expected, BankTransaction.Status.POSTED)) {
            throw new ConcurrentReconciliationException(payment.id());
        }
        for (var allocation : settlement.allocations()) {
            receivables.applyPayment(allocation.invoiceId(), allocation.amount());
        }
        ledger.post(payment.id(), source, journal(payment, settlement));
        log.info("posted {} via {}: {} kind={} fx={} charges={}", payment.bankReference(), source,
                settlement.invoiceNumbers(), settlement.kind(), settlement.fxDifference(), settlement.bankCharges());
        return publish(new PaymentReconciled(payment.id(), PaymentReconciled.Outcome.POSTED, source,
                settlement.invoiceNumbers()));
    }

    private PaymentReconciled review(BankTransaction payment, String reason, Optional<Investigation> investigation) {
        if (!payments.transition(payment.id(), BankTransaction.Status.RECEIVED, BankTransaction.Status.IN_REVIEW)) {
            throw new ConcurrentReconciliationException(payment.id());
        }
        reviews.open(payment.id(), reason, investigation);
        log.info("sent {} to review: {}", payment.bankReference(), reason);
        return publish(new PaymentReconciled(payment.id(), PaymentReconciled.Outcome.SENT_TO_REVIEW, "review",
                investigation.map(Investigation::invoiceNumbers).orElse(List.of())));
    }

    private PaymentReconciled publish(PaymentReconciled event) {
        events.publishEvent(event);
        return event;
    }

    private boolean withinAutoPostLimit(BankTransaction payment) {
        var inEur = fx.convert(payment.credited(), EUR, payment.valueDate());
        return inEur.compareTo(Money.of(tolerances.autoPostLimitEur(), EUR)) <= 0;
    }

    static List<LedgerLine> journal(BankTransaction payment, Settlement s) {
        var ref = payment.bankReference();
        var lines = new ArrayList<LedgerLine>();
        lines.add(LedgerLine.debit(LedgerLine.Account.CASH, s.received(), ref));
        if (s.bankCharges().isPositive()) {
            lines.add(LedgerLine.debit(LedgerLine.Account.BANK_CHARGES, s.bankCharges(), ref));
        }
        if (s.fxDifference().isPositive()) {
            lines.add(LedgerLine.debit(LedgerLine.Account.FX_DIFFERENCE, s.fxDifference(), ref));
        } else if (s.fxDifference().isNegative()) {
            lines.add(LedgerLine.credit(LedgerLine.Account.FX_DIFFERENCE, s.fxDifference().negate(), ref));
        }
        for (var a : s.allocations()) {
            lines.add(LedgerLine.credit(LedgerLine.Account.RECEIVABLES, a.amount(), a.invoiceNumber()));
        }
        if (s.unapplied().isPositive()) {
            lines.add(LedgerLine.credit(LedgerLine.Account.UNAPPLIED_CASH, s.unapplied(), ref));
        }
        return lines;
    }
}
