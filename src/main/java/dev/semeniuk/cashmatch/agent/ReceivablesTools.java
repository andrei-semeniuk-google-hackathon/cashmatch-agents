package dev.semeniuk.cashmatch.agent;

import dev.semeniuk.cashmatch.banking.Payments;
import dev.semeniuk.cashmatch.fx.FxRates;
import dev.semeniuk.cashmatch.invoicing.Invoice;
import dev.semeniuk.cashmatch.invoicing.Receivables;
import dev.semeniuk.cashmatch.money.Money;
import dev.semeniuk.cashmatch.reconciliation.SettlementCalculator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Read-only tools for the investigation agent, also published over MCP. None of them can
 * move money: the agent can look, compare and preview, and the decision stays with the policy.
 */
@Component
public class ReceivablesTools {

    private final Receivables receivables;
    private final FxRates fx;
    private final Payments payments;
    private final SettlementCalculator calculator;

    ReceivablesTools(Receivables receivables, FxRates fx, Payments payments, SettlementCalculator calculator) {
        this.receivables = receivables;
        this.fx = fx;
        this.payments = payments;
        this.calculator = calculator;
    }

    public record CustomerView(UUID id, String name, String country, double nameSimilarity) {}

    public record InvoiceView(String number, UUID customerId, String currency, BigDecimal total,
            BigDecimal open, LocalDate dueDate, String status) {

        static InvoiceView of(Invoice i) {
            return new InvoiceView(i.number(), i.customerId(), i.currency(), i.amount(), i.openAmount(),
                    i.dueDate(), i.status().name());
        }
    }

    public record SettlementPreview(boolean acceptable, String kind, String currency, BigDecimal allocated,
            BigDecimal bankCharges, BigDecimal fxDifference, BigDecimal unapplied, String rejectionReason) {}

    @Tool(description = """
            Fuzzy-search customers by name. Bank statements truncate and uppercase names and add
            legal forms or cities ("ACME ROBOTICS GMBH MUENCHEN"), so search with the distinctive part.""")
    public List<CustomerView> searchCustomers(@ToolParam(description = "Name or part of it") String name) {
        return receivables.searchCustomers(name, 5).stream()
                .map(m -> new CustomerView(m.customer().id(), m.customer().name(), m.customer().country(),
                        m.similarity()))
                .toList();
    }

    @Tool(description = "Find the customer that owns a bank account, if the IBAN is registered.")
    public CustomerView customerByIban(@ToolParam(description = "Payer IBAN") String iban) {
        return receivables.customerByIban(iban)
                .map(c -> new CustomerView(c.id(), c.name(), c.country(), 1.0))
                .orElse(null);
    }

    @Tool(description = "List a customer's unpaid and partially paid invoices, oldest due first.")
    public List<InvoiceView> openInvoices(@ToolParam(description = "Customer id") UUID customerId) {
        return receivables.openInvoices(customerId).stream().map(InvoiceView::of).toList();
    }

    @Tool(description = "Look up an invoice by a reference as written by a payer, e.g. 'inv 2026/105'.")
    public InvoiceView findInvoice(@ToolParam(description = "Invoice reference") String reference) {
        return receivables.invoice(reference).map(InvoiceView::of).orElse(null);
    }

    @Tool(description = "Convert an amount between currencies at the ECB reference rate for a date.")
    public BigDecimal convert(@ToolParam(description = "Amount") BigDecimal amount,
            @ToolParam(description = "ISO 4217 source currency") String from,
            @ToolParam(description = "ISO 4217 target currency") String to,
            @ToolParam(description = "Value date, yyyy-MM-dd") LocalDate date) {
        return fx.convert(Money.of(amount, Currency.getInstance(from)), Currency.getInstance(to), date).amount();
    }

    @Tool(description = """
            Preview exactly how a payment would settle a set of invoices, using the same calculation
            that posts to the ledger. Always call this before proposing a match.""")
    public SettlementPreview previewSettlement(
            @ToolParam(description = "Bank transaction id") UUID transactionId,
            @ToolParam(description = "Invoice numbers, e.g. [\"INV-2026-0105\"]") List<String> invoiceNumbers) {
        var payment = payments.find(transactionId).orElseThrow();
        return switch (calculator.settle(payment, receivables.invoices(invoiceNumbers))) {
            case SettlementCalculator.Result.Settled(var s) -> new SettlementPreview(true, s.kind().name(),
                    s.currency().getCurrencyCode(), s.allocated().amount(), s.bankCharges().amount(),
                    s.fxDifference().amount(), s.unapplied().amount(), null);
            case SettlementCalculator.Result.Rejected(var reason) ->
                    new SettlementPreview(false, null, null, null, null, null, null, reason);
        };
    }
}
