package dev.semeniuk.cashmatch.ledger;

import static dev.semeniuk.cashmatch.ledger.LedgerLine.Account.CASH;
import static dev.semeniuk.cashmatch.ledger.LedgerLine.Account.FX_DIFFERENCE;
import static dev.semeniuk.cashmatch.ledger.LedgerLine.Account.RECEIVABLES;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.semeniuk.cashmatch.money.Money;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerTest {

    @Test
    void acceptsBalancedEntries() {
        assertThatCode(() -> Ledger.assertBalanced(List.of(
                LedgerLine.debit(CASH, Money.of("9375.31", "CHF"), "r"),
                LedgerLine.debit(FX_DIFFERENCE, Money.of("4.69", "CHF"), "r"),
                LedgerLine.credit(RECEIVABLES, Money.of("9380.00", "CHF"), "INV-1"))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnEntryThatIsOffByOneCent() {
        assertThatThrownBy(() -> Ledger.assertBalanced(List.of(
                LedgerLine.debit(CASH, Money.of("100.00", "EUR"), "r"),
                LedgerLine.credit(RECEIVABLES, Money.of("99.99", "EUR"), "INV-1"))))
                .isInstanceOf(UnbalancedPostingException.class)
                .hasMessageContaining("0.01");
    }

    @Test
    void rejectsNonPositiveLines() {
        assertThatThrownBy(() -> LedgerLine.debit(CASH, Money.of("-1", "EUR"), "r"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
