package dev.semeniuk.cashmatch.reconciliation;

import static dev.semeniuk.cashmatch.reconciliation.Fixtures.VALUE_DATE;
import static dev.semeniuk.cashmatch.reconciliation.Fixtures.invoice;
import static dev.semeniuk.cashmatch.reconciliation.Fixtures.payment;
import static org.assertj.core.api.Assertions.assertThat;

import dev.semeniuk.cashmatch.banking.BankTransaction.ChargeBearer;
import dev.semeniuk.cashmatch.fx.FxRates;
import dev.semeniuk.cashmatch.ledger.LedgerLine;
import dev.semeniuk.cashmatch.money.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettlementCalculatorTest {

    static final FxRates ECB = (currency, date) -> Optional.ofNullable(Map.of(
            "EUR", new BigDecimal("1"),
            "USD", new BigDecimal("1.0850"),
            "GBP", new BigDecimal("0.8420"),
            "CHF", new BigDecimal("0.9380")).get(currency.getCurrencyCode()));

    final SettlementCalculator calculator = new SettlementCalculator(ECB,
            new Tolerances(new BigDecimal("0.005"), new BigDecimal("35.00"), new BigDecimal("25000"), 0.85));

    Settlement settled(SettlementCalculator.Result result) {
        assertThat(result).isInstanceOf(SettlementCalculator.Result.Settled.class);
        var settlement = ((SettlementCalculator.Result.Settled) result).settlement();
        assertBalanced(settlement);
        return settlement;
    }

    @Test
    void exactSameCurrencyPayment() {
        var s = settled(calculator.settle(payment("12400.00", "EUR"),
                List.of(invoice("INV-2026-0101", "12400.00", "EUR", VALUE_DATE))));
        assertThat(s.kind()).isEqualTo(Settlement.Kind.FULL);
        assertThat(s.fxDifference().isZero()).isTrue();
        assertThat(s.bankCharges().isZero()).isTrue();
    }

    @Test
    void shortSharedChargesPaymentIsSettledNetOfBankFees() {
        var s = settled(calculator.settle(
                payment(Money.of("4980.00", "GBP"), Optional.empty(), ChargeBearer.SHAR),
                List.of(invoice("INV-2026-0102", "2000.00", "GBP", VALUE_DATE),
                        invoice("INV-2026-0103", "3000.00", "GBP", VALUE_DATE))));
        assertThat(s.kind()).isEqualTo(Settlement.Kind.FULL_NET_OF_CHARGES);
        assertThat(s.bankCharges()).isEqualTo(Money.of("20.00", "GBP"));
        assertThat(s.allocated()).isEqualTo(Money.of("5000.00", "GBP"));
    }

    @Test
    void ourChargesMeansAShortPaymentIsReallyShort() {
        var s = settled(calculator.settle(
                payment(Money.of("4980.00", "GBP"), Optional.empty(), ChargeBearer.DEBT),
                List.of(invoice("INV-1", "5000.00", "GBP", VALUE_DATE))));
        assertThat(s.kind()).isEqualTo(Settlement.Kind.PARTIAL);
        assertThat(s.bankCharges().isZero()).isTrue();
    }

    @Test
    void crossCurrencyPaymentWithInstructedAmountBooksTheFxDifference() {
        // CHF invoice, EUR landed on our account, the payer instructed the exact CHF amount.
        var s = settled(calculator.settle(
                payment(Money.of("9995.00", "EUR"), Optional.of(Money.of("9380.00", "CHF")), ChargeBearer.SHAR),
                List.of(invoice("INV-2026-0104", "9380.00", "CHF", VALUE_DATE))));
        assertThat(s.kind()).isEqualTo(Settlement.Kind.FULL);
        assertThat(s.received()).isEqualTo(Money.of("9375.31", "CHF"));
        assertThat(s.fxDifference()).isEqualTo(Money.of("4.69", "CHF")); // a small FX loss
    }

    @Test
    void crossCurrencyWithoutInstructionUsesRelativeTolerance() {
        // 10 000 USD invoice paid with EUR 9 200: 9 982 USD at the reference rate, 0.18% off.
        var s = settled(calculator.settle(payment("9200.00", "EUR"),
                List.of(invoice("INV-1", "10000.00", "USD", VALUE_DATE))));
        assertThat(s.kind()).isEqualTo(Settlement.Kind.FULL);
        assertThat(s.fxDifference()).isEqualTo(Money.of("18.00", "USD"));
    }

    @Test
    void partialPaymentGoesToTheOldestDueInvoiceFirst() {
        var older = invoice("INV-OLD", "3000.00", "USD", VALUE_DATE.minusDays(20));
        var newer = invoice("INV-NEW", "4250.00", "USD", VALUE_DATE.minusDays(5));
        var s = settled(calculator.settle(payment("5000.00", "USD"), List.of(newer, older)));
        assertThat(s.kind()).isEqualTo(Settlement.Kind.PARTIAL);
        assertThat(s.allocations()).extracting(Settlement.Allocation::invoiceNumber, a -> a.amount().amount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("INV-OLD", new BigDecimal("3000.00")),
                        org.assertj.core.groups.Tuple.tuple("INV-NEW", new BigDecimal("2000.00")));
    }

    @Test
    void overpaymentLeavesUnappliedCash() {
        var s = settled(calculator.settle(payment("1500.00", "USD"),
                List.of(invoice("INV-1", "1200.00", "USD", VALUE_DATE))));
        assertThat(s.kind()).isEqualTo(Settlement.Kind.OVERPAYMENT);
        assertThat(s.unapplied()).isEqualTo(Money.of("300.00", "USD"));
    }

    @Test
    void largeFxGapMeansTheMatchIsWrong() {
        var result = calculator.settle(
                payment(Money.of("5000.00", "EUR"), Optional.of(Money.of("9380.00", "CHF")), ChargeBearer.SHAR),
                List.of(invoice("INV-1", "9380.00", "CHF", VALUE_DATE)));
        assertThat(result).isInstanceOfSatisfying(SettlementCalculator.Result.Rejected.class,
                r -> assertThat(r.reason()).contains("FX difference"));
    }

    @Test
    void rejectsMixedCustomersAndCurrencies() {
        assertThat(calculator.settle(payment("100.00", "USD"), List.of(
                invoice("A", "50.00", "USD", VALUE_DATE),
                invoice("B", "50.00", "USD", VALUE_DATE, UUID.randomUUID()))))
                .isInstanceOf(SettlementCalculator.Result.Rejected.class);
        assertThat(calculator.settle(payment("100.00", "USD"), List.of(
                invoice("A", "50.00", "USD", VALUE_DATE),
                invoice("B", "50.00", "EUR", VALUE_DATE))))
                .isInstanceOf(SettlementCalculator.Result.Rejected.class);
    }

    private static void assertBalanced(Settlement s) {
        var lines = Reconciliation.journal(Fixtures.payment("1", "EUR"), s);
        var net = lines.stream()
                .map(l -> l.side() == LedgerLine.Side.DEBIT ? l.amount().amount() : l.amount().amount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).as("journal for %s", s).isEqualByComparingTo("0");
    }
}
