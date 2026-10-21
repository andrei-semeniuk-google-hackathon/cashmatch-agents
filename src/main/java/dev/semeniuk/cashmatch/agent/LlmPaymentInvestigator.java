package dev.semeniuk.cashmatch.agent;

import dev.semeniuk.cashmatch.banking.BankTransaction;
import dev.semeniuk.cashmatch.reconciliation.Investigation;
import dev.semeniuk.cashmatch.reconciliation.PaymentInvestigator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Tool-using agent for payments the rules could not place: unknown payer accounts, missing
 * or mistyped references, several invoices paid together, a payment made by a subsidiary.
 *
 * <p>It returns an {@link Investigation} — who paid and for which invoices — and nothing else.
 * Failures degrade to "needs review"; an agent outage never blocks the payment queue.
 */
class LlmPaymentInvestigator implements PaymentInvestigator {

    private static final Logger log = LoggerFactory.getLogger(LlmPaymentInvestigator.class);

    static final String SYSTEM = """
            You are a cash-application analyst in a finance team that invoices companies worldwide.
            A payment arrived that automatic rules could not match. Work out who paid and which open
            invoices it settles, using only the tools. Never guess an id or invoice number.

            How to work:
            1. Identify the payer: customerByIban first, then searchCustomers with the distinctive part
               of the debtor name. Statement names are truncated, uppercased and include legal forms.
            2. Read the remittance text for invoice or PO hints; try findInvoice on anything that looks
               like an invoice reference, including typos.
            3. List the customer's open invoices and look for the invoice or combination the amount
               pays. Cross-currency payments differ slightly because of FX and bank fees.
            4. Call previewSettlement for your candidate. Only propose a match it accepts.

            Return MATCH with the customer id and invoice numbers, or NO_MATCH when the evidence is
            not conclusive. Confidence 0.9+ only when payer and amount both line up. A wrong match
            costs far more than a review, so prefer NO_MATCH over a guess. Explain your evidence in
            `reasoning` for the human reviewer.
            """;

    private final ChatClient chat;
    private final ReceivablesTools tools;

    LlmPaymentInvestigator(ChatClient chat, ReceivablesTools tools) {
        this.chat = chat;
        this.tools = tools;
    }

    @Override
    public Investigation investigate(BankTransaction payment) {
        try {
            var result = chat.prompt()
                    .system(SYSTEM)
                    .user(describe(payment))
                    .tools(tools)
                    .call()
                    .entity(Investigation.class);
            return result != null ? result : Investigation.noMatch("agent returned nothing");
        } catch (RuntimeException e) {
            log.warn("investigation of {} failed; routing to review", payment.bankReference(), e);
            return Investigation.noMatch("agent unavailable: " + e.getMessage());
        }
    }

    static String describe(BankTransaction p) {
        return """
                Incoming payment
                  transaction id:  %s
                  credited:        %s
                  instructed:      %s
                  bank charges:    %s (bearer %s)
                  value date:      %s
                  debtor name:     %s
                  debtor IBAN:     %s
                  remittance info: %s
                """.formatted(p.id(), p.credited(), p.instructed().map(Object::toString).orElse("not reported"),
                p.charges().map(Object::toString).orElse("not reported"), p.chargeBearer(), p.valueDate(),
                p.debtorName(), p.debtorIban().isBlank() ? "not provided" : p.debtorIban(), p.remittanceInfo());
    }
}
