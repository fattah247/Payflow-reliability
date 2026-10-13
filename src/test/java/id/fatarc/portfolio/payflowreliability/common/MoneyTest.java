package id.fatarc.portfolio.payflowreliability.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {
    @Test
    void shouldUseTheMinorUnitsOfEachCurrency() {
        assertThat(Money.fractionDigits("IDR")).isEqualTo(2);
        assertThat(Money.fractionDigits("JPY")).isZero();
        assertThat(Money.fractionDigits("KWD")).isEqualTo(3);
    }

    @Test
    void shouldRejectMorePrecisionThanTheCurrencyHas() {
        assertThat(Money.amountProblem(new BigDecimal("10.5"), "JPY")).hasValueSatisfying(problem ->
                assertThat(problem).contains("no minor unit"));
        assertThat(Money.amountProblem(new BigDecimal("10.505"), "USD")).isPresent();
        assertThat(Money.amountProblem(new BigDecimal("10.505"), "KWD")).isEmpty();
        assertThat(Money.amountProblem(new BigDecimal("10.500"), "USD")).as("trailing zeros are not extra precision").isEmpty();
    }

    @Test
    void shouldRejectZeroNegativeAndOversizedAmounts() {
        assertThat(Money.amountProblem(BigDecimal.ZERO, "IDR")).isPresent();
        assertThat(Money.amountProblem(new BigDecimal("-1"), "IDR")).isPresent();
        assertThat(Money.amountProblem(new BigDecimal("1000000000000000"), "IDR")).isPresent();
        assertThat(Money.amountProblem(new BigDecimal("999999999999999.99"), "IDR")).isEmpty();
    }

    @Test
    void shouldRejectCodesThatAreNotIsoCurrencies() {
        assertThat(Money.isSupportedCurrency("IDR")).isTrue();
        assertThat(Money.isSupportedCurrency("ABC")).isFalse();
        assertThat(Money.isSupportedCurrency("XXX")).as("'no currency' is not a payment currency").isFalse();
    }

    @Test
    void shouldNormalizeWithoutRounding() {
        assertThat(Money.normalize(new BigDecimal("10"), "USD")).isEqualTo(new BigDecimal("10.00"));
        assertThatThrownBy(() -> Money.normalize(new BigDecimal("10.001"), "USD")).isInstanceOf(ArithmeticException.class);
    }
}
