package dev.semeniuk.cashmatch.invoicing;

import dev.semeniuk.cashmatch.money.Money;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The invoicing module's public API: who owes what. */
@Service
public class Receivables {

    /** Invoice numbers as they appear in remittance text: "INV-2026-0042", "inv 2026 42", ... */
    public static final Pattern INVOICE_REFERENCE =
            Pattern.compile("(?i)\\bINV[\\s\\-/_]*(\\d{4})[\\s\\-/_]*(\\d{1,6})\\b");

    private final InvoiceRepository invoices;
    private final JdbcClient jdbc;

    Receivables(InvoiceRepository invoices, JdbcClient jdbc) {
        this.invoices = invoices;
        this.jdbc = jdbc;
    }

    /** Canonical form of a free-text invoice reference, or empty when it is not one. */
    public static Optional<String> canonicalInvoiceNumber(String text) {
        var m = INVOICE_REFERENCE.matcher(text);
        return m.find()
                ? Optional.of("INV-%s-%04d".formatted(m.group(1), Integer.parseInt(m.group(2))))
                : Optional.empty();
    }

    /** Every invoice number referenced anywhere in a remittance text, in order of appearance. */
    public static List<String> invoiceNumbersIn(String text) {
        return INVOICE_REFERENCE.matcher(text).results()
                .map(m -> "INV-%s-%04d".formatted(m.group(1), Integer.parseInt(m.group(2))))
                .distinct()
                .toList();
    }

    public Optional<Invoice> invoice(String reference) {
        return canonicalInvoiceNumber(reference).flatMap(invoices::findByNumber);
    }

    public List<Invoice> invoices(Collection<String> numbers) {
        return invoices.findByNumberIn(numbers);
    }

    public List<Invoice> openInvoices(UUID customerId) {
        return invoices.findByCustomerIdAndStatusNotOrderByDueDateAscNumberAsc(
                customerId, Invoice.Status.PAID);
    }

    public Optional<Customer> customer(UUID id) {
        return jdbc.sql("SELECT id, name, country FROM customers WHERE id = :id")
                .param("id", id)
                .query(Customer.class)
                .optional();
    }

    public Optional<Customer> customerByIban(String iban) {
        return jdbc.sql("""
                        SELECT c.id, c.name, c.country FROM customers c
                        JOIN customer_accounts a ON a.customer_id = c.id
                        WHERE a.iban = :iban
                        """)
                .param("iban", normalizeIban(iban))
                .query(Customer.class)
                .optional();
    }

    /** Trigram search, so "ACME GMBH MUENCHEN" still finds "Acme GmbH". */
    public List<CustomerMatch> searchCustomers(String name, int limit) {
        return jdbc.sql("""
                        SELECT id, name, country, similarity(name, :q) AS score FROM customers
                        WHERE similarity(name, :q) > 0.2 OR name ILIKE '%' || :q || '%'
                        ORDER BY score DESC LIMIT :limit
                        """)
                .param("q", name)
                .param("limit", limit)
                .query((rs, i) -> new CustomerMatch(
                        new Customer(rs.getObject("id", UUID.class), rs.getString("name"),
                                rs.getString("country")),
                        rs.getDouble("score")))
                .list();
    }

    /** Reduce an invoice's open amount. Optimistic locking rejects concurrent double-posting. */
    @Transactional
    public Invoice applyPayment(UUID invoiceId, Money amount) {
        var invoice = invoices.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("unknown invoice " + invoiceId));
        return invoices.save(invoice.settle(amount));
    }

    public static String normalizeIban(String iban) {
        return iban.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }
}
