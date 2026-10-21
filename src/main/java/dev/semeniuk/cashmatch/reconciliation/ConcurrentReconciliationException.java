package dev.semeniuk.cashmatch.reconciliation;

import java.util.UUID;

/** Another worker handled this payment first. Its transaction rolls back; nothing is booked twice. */
public class ConcurrentReconciliationException extends IllegalStateException {
    public ConcurrentReconciliationException(UUID transactionId) {
        super("payment " + transactionId + " was reconciled concurrently");
    }
}
