package dev.semeniuk.cashmatch.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.semeniuk.cashmatch.TestcontainersConfiguration;
import dev.semeniuk.cashmatch.agent.ReceivablesTools;
import dev.semeniuk.cashmatch.banking.BankTransaction;
import dev.semeniuk.cashmatch.banking.Payments;
import dev.semeniuk.cashmatch.invoicing.Invoice;
import dev.semeniuk.cashmatch.invoicing.Receivables;
import dev.semeniuk.cashmatch.ledger.Ledger;
import dev.semeniuk.cashmatch.ledger.LedgerLine;
import dev.semeniuk.cashmatch.money.Money;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole flow on real Postgres: statement upload, outbox event, rules, a scripted agent,
 * the auto-post policy, the review queue and the ledger. Only the LLM is replaced.
 *
 * <p>The methods are ordered on purpose: together they are one business day on one ledger,
 * and each step asserts the state the previous one left behind.
 */
@SpringBootTest(properties = {"cashmatch.agent.enabled=false", "spring.ai.model.chat=none"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ReconciliationFlowIT.ScriptedAgent.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReconciliationFlowIT {

    static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SAKURA = UUID.fromString("44444444-4444-4444-4444-444444444444");

    /** Stands in for the LLM: answers per bank reference, the way a recorded eval would. */
    @TestConfiguration
    static class ScriptedAgent {
        static final Map<String, Investigation> ANSWERS = new ConcurrentHashMap<>(Map.of(
                "BNK-20261020-0004", new Investigation(Investigation.Verdict.MATCH, SAKURA,
                        List.of("INV-2026-0105", "INV-2026-0106"), 0.93,
                        "Debtor name matches Sakura Components K.K.; 3000 + 4250 = 7250 USD."),
                "BNK-20261021-0001", new Investigation(Investigation.Verdict.MATCH, ACME,
                        List.of("INV-2026-0107"), 0.97, "Confidently wrong on purpose.")));

        @Bean
        PaymentInvestigator scriptedInvestigator() {
            return payment -> ANSWERS.getOrDefault(payment.bankReference(),
                    Investigation.noMatch("No customer resembles the debtor and the reference is empty."));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired Payments payments;
    @Autowired Receivables receivables;
    @Autowired Ledger ledger;
    @Autowired JdbcClient jdbc;
    @Autowired ReceivablesTools tools;

    @Test
    @Order(1)
    void reconcilesAStatementEndToEnd() throws Exception {
        mvc.perform(post("/api/statements").contentType(MediaType.APPLICATION_XML).content(sample()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(7))
                .andExpect(jsonPath("$.duplicates").value(0));

        await().atMost(Duration.ofSeconds(20)).until(() -> payments.byStatus(BankTransaction.Status.RECEIVED).isEmpty());

        // Rule: invoice reference, exact.
        assertThat(statusOf("BNK-20261020-0001")).isEqualTo(BankTransaction.Status.POSTED);
        assertThat(open("INV-2026-0101")).isEqualTo(Money.of("0.00", "EUR"));

        // Rule: two references, 20 GBP short under shared charges -> both paid, fee booked.
        assertThat(open("INV-2026-0102")).isEqualTo(Money.of("0.00", "GBP"));
        assertThat(open("INV-2026-0103")).isEqualTo(Money.of("0.00", "GBP"));
        assertThat(journal("BNK-20261020-0002")).contains(
                LedgerLine.debit(LedgerLine.Account.BANK_CHARGES, Money.of("20.00", "GBP"), "BNK-20261020-0002"));

        // Rule: known IBAN, no reference, EUR received for a CHF invoice -> FX loss booked.
        assertThat(open("INV-2026-0104")).isEqualTo(Money.of("0.00", "CHF"));
        assertThat(journal("BNK-20261020-0003")).contains(
                LedgerLine.debit(LedgerLine.Account.FX_DIFFERENCE, Money.of("4.69", "CHF"), "BNK-20261020-0003"));
        assertThat(open("INV-2026-0111")).as("the other Helvetia invoice is untouched")
                .isEqualTo(Money.of("2200.00", "CHF"));

        // Agent: unknown account, no reference, two invoices paid together.
        assertThat(statusOf("BNK-20261020-0004")).isEqualTo(BankTransaction.Status.POSTED);
        assertThat(source("BNK-20261020-0004")).isEqualTo("agent");
        assertThat(open("INV-2026-0105").isZero()).isTrue();
        assertThat(open("INV-2026-0106").isZero()).isTrue();
        assertThat(open("INV-2026-0107")).isEqualTo(Money.of("1200.00", "USD"));

        // Agent found nothing -> review.
        assertThat(statusOf("BNK-20261020-0005")).isEqualTo(BankTransaction.Status.IN_REVIEW);

        // Rule: partial payment against a referenced invoice.
        var maple = receivables.invoice("INV-2026-0108").orElseThrow();
        assertThat(maple.open()).isEqualTo(Money.of("5000.00", "USD"));
        assertThat(maple.status()).isEqualTo(Invoice.Status.PARTIALLY_PAID);

        // Rule matched, but 48 000 USD is above the auto-post limit -> review.
        assertThat(statusOf("BNK-20261020-0007")).isEqualTo(BankTransaction.Status.IN_REVIEW);
        assertThat(open("INV-2026-0109")).isEqualTo(Money.of("48000.00", "USD"));

        assertEveryJournalBalances();
    }

    @Test
    @Order(2)
    void resendingTheSameStatementBooksNothingTwice() throws Exception {
        mvc.perform(post("/api/statements").contentType(MediaType.APPLICATION_XML).content(sample()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(0))
                .andExpect(jsonPath("$.duplicates").value(7));
        assertThat(jdbc.sql("SELECT count(*) FROM journal_entries").query(Long.class).single()).isEqualTo(5);
    }

    @Test
    @Order(3)
    void aConfidentButWrongAgentIsStoppedByThePolicy() throws Exception {
        var statement = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08"><BkToCstmrStmt><Stmt>
                  <Ntry><Amt Ccy="USD">1200.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts><Cd>BOOK</Cd></Sts>
                    <ValDt><Dt>2026-10-21</Dt></ValDt><AcctSvcrRef>BNK-20261021-0001</AcctSvcrRef>
                    <NtryDtls><TxDtls><Chrgs><Rcrd><Amt Ccy="USD">0.00</Amt><Br>DEBT</Br></Rcrd></Chrgs>
                      <RltdPties><Dbtr><Pty><Nm>SAKURA COMPONENTS KK</Nm></Pty></Dbtr></RltdPties>
                      <RmtInf><Ustrd>PO 4490</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>
                </Stmt></BkToCstmrStmt></Document>
                """;
        mvc.perform(post("/api/statements").contentType(MediaType.APPLICATION_XML).content(statement))
                .andExpect(jsonPath("$.received").value(1));
        await().atMost(Duration.ofSeconds(20)).until(() -> statusOf("BNK-20261021-0001") != BankTransaction.Status.RECEIVED);

        assertThat(statusOf("BNK-20261021-0001")).isEqualTo(BankTransaction.Status.IN_REVIEW);
        assertThat(reviewReason("BNK-20261021-0001")).contains("does not own");
        assertThat(open("INV-2026-0107")).isEqualTo(Money.of("1200.00", "USD"));
    }

    @Test
    @Order(4)
    void aReviewerApprovesWithTheirOwnSelection() throws Exception {
        var reviewId = reviewId("BNK-20261021-0001");
        mvc.perform(post("/api/reviews/{id}/approve", reviewId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewer\": \"anna@finance\", \"invoiceNumbers\": [\"INV-2026-0107\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("POSTED"))
                .andExpect(jsonPath("$.source").value("review:anna@finance"));

        assertThat(open("INV-2026-0107").isZero()).isTrue();
        mvc.perform(get("/api/reviews"))
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(reviewId)).doesNotExist());
        assertEveryJournalBalances();
    }

    @Test
    @Order(5)
    void aReviewerCannotApproveAnAllocationTheCalculatorRejects() throws Exception {
        var reviewId = reviewId("BNK-20261020-0005"); // EUR 1 500 from an unknown payer
        mvc.perform(post("/api/reviews/{id}/approve", reviewId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewer\": \"anna@finance\", \"invoiceNumbers\": [\"INV-2026-0110\", \"INV-2026-0111\"]}"))
                .andExpect(status().isUnprocessableContent());
        assertThat(statusOf("BNK-20261020-0005")).isEqualTo(BankTransaction.Status.IN_REVIEW);
    }

    @Test
    @Order(6)
    void theAgentToolsSeeTheSameDataAsThePolicy() {
        var match = tools.searchCustomers("SAKURA COMPONENTS KK TOKYO").getFirst();
        assertThat(match.id()).isEqualTo(SAKURA);

        assertThat(tools.findInvoice("inv 2026/110").number()).isEqualTo("INV-2026-0110");
        assertThat(tools.openInvoices(ACME)).extracting(ReceivablesTools.InvoiceView::number)
                .containsExactly("INV-2026-0110");

        var unknownPayer = txId("BNK-20261020-0005"); // EUR 1 500, still in review
        var preview = tools.previewSettlement(unknownPayer, List.of("INV-2026-0110"));
        assertThat(preview.acceptable()).isTrue();
        assertThat(preview.kind()).isEqualTo("PARTIAL");
        assertThat(preview.allocated()).isEqualByComparingTo("1500.00");

        var wrong = tools.previewSettlement(unknownPayer, List.of("INV-2026-0110", "INV-2026-0111"));
        assertThat(wrong.acceptable()).isFalse();
        assertThat(wrong.rejectionReason()).contains("different");
    }

    @Test
    @Order(7)
    void rejectingTwiceIsRefused() throws Exception {
        var reviewId = reviewId("BNK-20261020-0007");
        mvc.perform(post("/api/reviews/{id}/reject", reviewId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewer\": \"anna@finance\"}"))
                .andExpect(status().isOk());
        assertThat(statusOf("BNK-20261020-0007")).isEqualTo(BankTransaction.Status.UNMATCHED);
        mvc.perform(post("/api/reviews/{id}/reject", reviewId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewer\": \"anna@finance\"}"))
                .andExpect(status().isUnprocessableContent());
    }

    private void assertEveryJournalBalances() {
        var unbalanced = jdbc.sql("""
                        SELECT entry_id, currency,
                               SUM(CASE side WHEN 'DEBIT' THEN amount ELSE -amount END) AS net
                        FROM journal_lines GROUP BY entry_id, currency
                        HAVING SUM(CASE side WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0
                        """).query().listOfRows();
        assertThat(unbalanced).isEmpty();
    }

    private String sample() throws IOException {
        try (var in = getClass().getResourceAsStream("/samples/camt053-2026-10-20.xml")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private Money open(String invoice) {
        return receivables.invoice(invoice).orElseThrow().open();
    }

    private UUID txId(String bankReference) {
        return jdbc.sql("SELECT id FROM bank_transactions WHERE bank_reference = :r")
                .param("r", bankReference).query(UUID.class).single();
    }

    private BankTransaction.Status statusOf(String bankReference) {
        return payments.find(txId(bankReference)).orElseThrow().status();
    }

    private List<LedgerLine> journal(String bankReference) {
        return ledger.lines(txId(bankReference));
    }

    private String source(String bankReference) {
        return jdbc.sql("SELECT source FROM journal_entries WHERE transaction_id = :tx")
                .param("tx", txId(bankReference)).query(String.class).single();
    }

    private UUID reviewId(String bankReference) {
        return jdbc.sql("SELECT id FROM review_tasks WHERE transaction_id = :tx AND status = 'OPEN'")
                .param("tx", txId(bankReference)).query(UUID.class).single();
    }

    private String reviewReason(String bankReference) {
        return jdbc.sql("SELECT reason FROM review_tasks WHERE transaction_id = :tx")
                .param("tx", txId(bankReference)).query(String.class).single();
    }

}
