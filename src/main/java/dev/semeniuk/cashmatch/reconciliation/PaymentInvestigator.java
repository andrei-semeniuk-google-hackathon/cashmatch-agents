package dev.semeniuk.cashmatch.reconciliation;

import dev.semeniuk.cashmatch.banking.BankTransaction;

/**
 * Port for whatever investigates payments the rules could not place. The agent module
 * provides the LLM implementation; without it every such payment goes to a human.
 */
public interface PaymentInvestigator {

    Investigation investigate(BankTransaction payment);
}
