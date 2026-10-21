package dev.semeniuk.cashmatch.ledger;

import dev.semeniuk.cashmatch.money.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Double-entry journal. A posting is balanced per currency or it is not written at all. */
@Service
public class Ledger {

    private final JdbcClient jdbc;

    Ledger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes one journal entry for a bank transaction. Posting the same transaction twice fails
     * on the unique key, so a redelivered event can never book cash twice.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(UUID transactionId, String source, List<LedgerLine> lines) {
        assertBalanced(lines);
        var entryId = UUID.randomUUID();
        try {
            jdbc.sql("INSERT INTO journal_entries (id, transaction_id, source) VALUES (:id, :tx, :src)")
                    .param("id", entryId).param("tx", transactionId).param("src", source)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new AlreadyPostedException(transactionId);
        }
        for (var line : lines) {
            jdbc.sql("""
                            INSERT INTO journal_lines (entry_id, account, side, amount, currency, reference)
                            VALUES (:entry, :account, :side, :amount, :currency, :reference)
                            """)
                    .param("entry", entryId)
                    .param("account", line.account().name())
                    .param("side", line.side().name())
                    .param("amount", line.amount().amount())
                    .param("currency", line.amount().currency().getCurrencyCode())
                    .param("reference", line.reference())
                    .update();
        }
        return entryId;
    }

    public List<LedgerLine> lines(UUID transactionId) {
        return jdbc.sql("""
                        SELECT l.account, l.side, l.amount, l.currency, l.reference
                        FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                        WHERE e.transaction_id = :tx ORDER BY l.id
                        """)
                .param("tx", transactionId)
                .query((rs, i) -> new LedgerLine(
                        LedgerLine.Account.valueOf(rs.getString("account")),
                        LedgerLine.Side.valueOf(rs.getString("side")),
                        Money.of(rs.getBigDecimal("amount"), Currency.getInstance(rs.getString("currency"))),
                        rs.getString("reference")))
                .list();
    }

    static void assertBalanced(List<LedgerLine> lines) {
        var byCurrency = lines.stream().collect(Collectors.groupingBy(
                l -> l.amount().currency(),
                Collectors.reducing(BigDecimal.ZERO,
                        l -> l.side() == LedgerLine.Side.DEBIT ? l.amount().amount() : l.amount().amount().negate(),
                        BigDecimal::add)));
        byCurrency.forEach((currency, net) -> {
            if (net.signum() != 0) {
                throw new UnbalancedPostingException("entry is off by " + net + " " + currency);
            }
        });
    }

    public static class AlreadyPostedException extends IllegalStateException {
        public AlreadyPostedException(UUID transactionId) {
            super("transaction " + transactionId + " is already posted");
        }
    }
}
