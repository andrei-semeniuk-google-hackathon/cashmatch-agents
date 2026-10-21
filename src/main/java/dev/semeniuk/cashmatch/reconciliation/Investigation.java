package dev.semeniuk.cashmatch.reconciliation;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;
import java.util.UUID;

/**
 * What an investigator concluded. It names the customer and invoices; it never names amounts.
 * Amounts are always computed by {@link SettlementCalculator}.
 */
public record Investigation(
        @JsonPropertyDescription("MATCH when you identified the invoices, otherwise NO_MATCH")
        Verdict verdict,
        @JsonPropertyDescription("The paying customer's id, when identified")
        UUID customerId,
        @JsonPropertyDescription("Invoice numbers exactly as returned by the tools, e.g. INV-2026-0105")
        List<String> invoiceNumbers,
        @JsonPropertyDescription("0.0 to 1.0: how sure you are that this allocation is correct")
        double confidence,
        @JsonPropertyDescription("Evidence for the decision, for the finance reviewer")
        String reasoning) {

    public enum Verdict { MATCH, NO_MATCH }

    public Investigation {
        invoiceNumbers = invoiceNumbers == null ? List.of() : List.copyOf(invoiceNumbers);
    }

    public static Investigation noMatch(String reasoning) {
        return new Investigation(Verdict.NO_MATCH, null, List.of(), 0.0, reasoning);
    }
}
