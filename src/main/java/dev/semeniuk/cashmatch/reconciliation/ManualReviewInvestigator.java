package dev.semeniuk.cashmatch.reconciliation;

import dev.semeniuk.cashmatch.banking.BankTransaction;

/** Fallback when no agent is configured: route everything the rules missed to a human. */
class ManualReviewInvestigator implements PaymentInvestigator {

    @Override
    public Investigation investigate(BankTransaction payment) {
        return Investigation.noMatch("No investigation agent configured; needs manual review.");
    }
}
