package dev.semeniuk.cashmatch.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.semeniuk.cashmatch.money.Money;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Camt053ParserTest {

    final Camt053Parser parser = new Camt053Parser();

    @Test
    void readsBookedCreditsAndSkipsDebits() {
        var transactions = parser.parse(getClass().getResourceAsStream("/samples/camt053-2026-10-20.xml"));

        assertThat(transactions).hasSize(7);
        assertThat(transactions).extracting(BankTransaction::bankReference).doesNotContain("BNK-20261020-9001");

        var helvetia = transactions.get(2);
        assertThat(helvetia.credited()).isEqualTo(Money.of("9995.00", "EUR"));
        assertThat(helvetia.instructed()).contains(Money.of("9380.00", "CHF"));
        assertThat(helvetia.matchingAmount()).isEqualTo(Money.of("9380.00", "CHF"));
        assertThat(helvetia.debtorIban()).isEqualTo("CH9300762011623852957");
        assertThat(helvetia.chargeBearer()).isEqualTo(BankTransaction.ChargeBearer.SHAR);
        assertThat(helvetia.charges()).as("zero charges are not reported charges").isEmpty();

        var sakura = transactions.get(3);
        assertThat(sakura.debtorName()).isEqualTo("SAKURA COMPONENTS KK TOKYO");
        assertThat(sakura.debtorIban()).isEmpty();
        assertThat(sakura.remittanceInfo()).isEqualTo("PO 4471/4472 OCT SHIPMENTS");
    }

    @Test
    void readsTheOlderCamt053v02Layout() {
        var xml = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"><BkToCstmrStmt><Stmt>
                  <Ntry><Amt Ccy="USD">100.50</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts>BOOK</Sts>
                    <ValDt><Dt>2026-10-19</Dt></ValDt><AcctSvcrRef>R1</AcctSvcrRef>
                    <NtryDtls><TxDtls><RltdPties><Dbtr><Nm>OLD BANK CLIENT</Nm></Dbtr></RltdPties>
                    <RmtInf><Ustrd>INV-2026-0001</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>
                </Stmt></BkToCstmrStmt></Document>
                """;
        var t = parser.parse(stream(xml)).getFirst();
        assertThat(t.credited()).isEqualTo(Money.of("100.50", "USD"));
        assertThat(t.debtorName()).isEqualTo("OLD BANK CLIENT");
        assertThat(t.chargeBearer()).isEqualTo(BankTransaction.ChargeBearer.UNKNOWN);
    }

    @Test
    void splitsABatchBookedEntryIntoItsPayments() {
        var xml = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08"><BkToCstmrStmt><Stmt>
                  <Ntry><Amt Ccy="EUR">3000.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts><Cd>BOOK</Cd></Sts>
                    <ValDt><Dt>2026-10-20</Dt></ValDt><AcctSvcrRef>BATCH-7</AcctSvcrRef>
                    <NtryDtls>
                      <TxDtls><Amt Ccy="EUR">1000.00</Amt>
                        <RltdPties><Dbtr><Pty><Nm>FIRST GMBH</Nm></Pty></Dbtr></RltdPties>
                        <RmtInf><Ustrd>INV-2026-0001</Ustrd></RmtInf></TxDtls>
                      <TxDtls><AmtDtls><TxAmt><Amt Ccy="EUR">2000.00</Amt></TxAmt></AmtDtls>
                        <RltdPties><Dbtr><Pty><Nm>SECOND SARL</Nm></Pty></Dbtr></RltdPties>
                        <RmtInf><Ustrd>INV-2026-0002</Ustrd></RmtInf></TxDtls>
                    </NtryDtls></Ntry>
                </Stmt></BkToCstmrStmt></Document>
                """;
        var transactions = parser.parse(stream(xml));
        assertThat(transactions).extracting(BankTransaction::bankReference).containsExactly("BATCH-7/1", "BATCH-7/2");
        assertThat(transactions).extracting(BankTransaction::credited)
                .containsExactly(Money.of("1000.00", "EUR"), Money.of("2000.00", "EUR"));
        assertThat(transactions).extracting(BankTransaction::debtorName).containsExactly("FIRST GMBH", "SECOND SARL");
    }

    @Test
    void rejectsExternalEntities() {
        var xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <Document><BkToCstmrStmt><Stmt><Ntry><AcctSvcrRef>&x;</AcctSvcrRef></Ntry></Stmt></BkToCstmrStmt></Document>
                """;
        assertThatThrownBy(() -> parser.parse(stream(xxe))).isInstanceOf(StatementParseException.class);
    }

    private static ByteArrayInputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
