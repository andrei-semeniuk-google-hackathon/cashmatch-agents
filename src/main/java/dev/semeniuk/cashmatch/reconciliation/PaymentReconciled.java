package dev.semeniuk.cashmatch.reconciliation;

import java.util.List;
import java.util.UUID;
import org.jmolecules.event.annotation.DomainEvent;

@DomainEvent
public record PaymentReconciled(UUID transactionId, Outcome outcome, String source, List<String> invoices) {

    public enum Outcome { POSTED, SENT_TO_REVIEW, ALREADY_HANDLED }
}
