package dev.semeniuk.cashmatch.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void scalesToTheCurrencyMinorUnitWithBankersRounding() {
        assertThat(Money.of("10.125", "EUR").amount()).isEqualByComparingTo("10.12");
        assertThat(Money.of("10.135", "EUR").amount()).isEqualByComparingTo("10.14");
        assertThat(Money.of("1500.6", "JPY").amount()).isEqualByComparingTo("1501");
        assertThat(Money.of("1.2345", "BHD").amount()).isEqualByComparingTo("1.234"); // 3 decimals
    }

    @Test
    void refusesToMixCurrencies() {
        assertThatThrownBy(() -> Money.of("1", "EUR").plus(Money.of("1", "USD")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void comparesWithinTolerance() {
        var a = Money.of("100.00", "USD");
        assertThat(a.isWithin(Money.of("0.50", "USD"), Money.of("100.40", "USD"))).isTrue();
        assertThat(a.isWithin(Money.of("0.50", "USD"), Money.of("100.60", "USD"))).isFalse();
        assertThat(a.times(new BigDecimal("0.005"))).isEqualTo(Money.of("0.50", "USD"));
    }
}
