package dev.semeniuk.cashmatch.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A payment a human has to decide on, with everything the machine found so far. */
public record ReviewTask(
        UUID id,
        UUID transactionId,
        String reason,
        List<String> proposedInvoices,
        Double agentConfidence,
        String agentReasoning,
        Status status,
        String decidedBy,
        Instant createdAt) {

    public enum Status { OPEN, APPROVED, REJECTED }
}
