package dev.semeniuk.cashmatch.banking;

import dev.semeniuk.cashmatch.money.Money;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The banking module's public API: statements in, payments out. */
@Service
public class Payments {

    private final Camt053Parser parser;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    Payments(Camt053Parser parser, JdbcClient jdbc, ApplicationEventPublisher events) {
        this.parser = parser;
        this.jdbc = jdbc;
        this.events = events;
    }

    public record IngestionResult(int received, int duplicates, List<UUID> transactionIds) {}

    /**
     * Stores new credits and publishes {@link PaymentReceived} for each, in one transaction.
     * Re-sending the same statement is safe: the bank reference is unique.
     */
    @Transactional
    public IngestionResult ingest(InputStream camt053) {
        var parsed = parser.parse(camt053);
        var stored = parsed.stream().filter(this::insertIfNew).map(BankTransaction::id).toList();
        stored.forEach(id -> events.publishEvent(new PaymentReceived(id)));
        return new IngestionResult(stored.size(), parsed.size() - stored.size(), stored);
    }

    public Optional<BankTransaction> find(UUID id) {
        return jdbc.sql("SELECT * FROM bank_transactions WHERE id = :id")
                .param("id", id)
                .query(Payments::map)
                .optional();
    }

    public List<BankTransaction> byStatus(BankTransaction.Status status) {
        return jdbc.sql("SELECT * FROM bank_transactions WHERE status = :s ORDER BY value_date")
                .param("s", status.name())
                .query(Payments::map)
                .list();
    }

    /**
     * Compare-and-set on the status. Concurrent reconcilers of the same payment race here,
     * and only one of them wins.
     */
    public boolean transition(UUID id, BankTransaction.Status from, BankTransaction.Status to) {
        return jdbc.sql("UPDATE bank_transactions SET status = :to WHERE id = :id AND status = :from")
                .param("to", to.name())
                .param("from", from.name())
                .param("id", id)
                .update() == 1;
    }

    private boolean insertIfNew(BankTransaction t) {
        return jdbc.sql("""
                        INSERT INTO bank_transactions (id, bank_reference, end_to_end_id,
                            credited_amount, credited_currency, instructed_amount,
                            instructed_currency, charges_amount, charges_currency, charge_bearer,
                            value_date, debtor_name, debtor_iban, remittance_info, status)
                        VALUES (:id, :ref, :e2e, :ca, :cc, :ia, :ic, :cha, :chc, :bearer,
                            :vd, :dn, :di, :ri, :status)
                        ON CONFLICT (bank_reference) DO NOTHING
                        """)
                .param("id", t.id())
                .param("ref", t.bankReference())
                .param("e2e", t.endToEndId())
                .param("ca", t.credited().amount())
                .param("cc", t.credited().currency().getCurrencyCode())
                .param("ia", t.instructed().map(Money::amount).orElse(null))
                .param("ic", t.instructed().map(m -> m.currency().getCurrencyCode()).orElse(null))
                .param("cha", t.charges().map(Money::amount).orElse(null))
                .param("chc", t.charges().map(m -> m.currency().getCurrencyCode()).orElse(null))
                .param("bearer", t.chargeBearer().name())
                .param("vd", t.valueDate())
                .param("dn", t.debtorName())
                .param("di", t.debtorIban())
                .param("ri", t.remittanceInfo())
                .param("status", t.status().name())
                .update() == 1;
    }

    private static BankTransaction map(ResultSet rs, int row) throws SQLException {
        return new BankTransaction(
                rs.getObject("id", UUID.class),
                rs.getString("bank_reference"),
                rs.getString("end_to_end_id"),
                money(rs.getBigDecimal("credited_amount"), rs.getString("credited_currency"))
                        .orElseThrow(),
                money(rs.getBigDecimal("instructed_amount"), rs.getString("instructed_currency")),
                money(rs.getBigDecimal("charges_amount"), rs.getString("charges_currency")),
                BankTransaction.ChargeBearer.valueOf(rs.getString("charge_bearer")),
                rs.getObject("value_date", LocalDate.class),
                rs.getString("debtor_name"),
                rs.getString("debtor_iban"),
                rs.getString("remittance_info"),
                BankTransaction.Status.valueOf(rs.getString("status")));
    }

    private static Optional<Money> money(BigDecimal amount, String currency) {
        return Optional.ofNullable(amount).map(a -> Money.of(a, Currency.getInstance(currency)));
    }
}
