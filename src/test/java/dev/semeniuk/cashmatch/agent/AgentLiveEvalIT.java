package dev.semeniuk.cashmatch.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.semeniuk.cashmatch.TestcontainersConfiguration;
import dev.semeniuk.cashmatch.banking.BankTransaction;
import dev.semeniuk.cashmatch.banking.Payments;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The real model against the sample statement. Runs only with ANTHROPIC_API_KEY set
 * (CI runs it on main), because it costs tokens and depends on a network.
 */
@SpringBootTest(properties = "cashmatch.agent.enabled=true")
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class AgentLiveEvalIT {

    @Autowired Payments payments;
    @Autowired JdbcClient jdbc;

    @Test
    void agentPlacesTheAmbiguousPaymentAndRefusesTheUnknownOne() throws Exception {
        try (var statement = getClass().getResourceAsStream("/samples/camt053-2026-10-20.xml")) {
            payments.ingest(statement);
        }
        await().atMost(Duration.ofMinutes(2)).until(() -> payments.byStatus(BankTransaction.Status.RECEIVED).isEmpty());

        // Sakura: no IBAN, no invoice number, two invoices paid together.
        assertThat(sourceOf("BNK-20261020-0004")).isEqualTo("agent");
        // An unknown payer must not be "matched" to something that happens to fit.
        assertThat(statusOf("BNK-20261020-0005")).isEqualTo("IN_REVIEW");
    }

    private String statusOf(String ref) {
        return jdbc.sql("SELECT status FROM bank_transactions WHERE bank_reference = :r")
                .param("r", ref).query(String.class).single();
    }

    private String sourceOf(String ref) {
        return jdbc.sql("""
                        SELECT e.source FROM journal_entries e
                        JOIN bank_transactions t ON t.id = e.transaction_id WHERE t.bank_reference = :r
                        """).param("r", ref).query(String.class).optional().orElse("not posted");
    }
}
