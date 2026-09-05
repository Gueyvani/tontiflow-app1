package com.tontiflow.domain.model;

import com.tontiflow.domain.enums.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitaires de {@link Money} (Money Model Option C, décision Phase R/R2).
 */
class MoneyTest {

    @Test
    void of_withValidMruAmount_succeeds() {
        Money money = Money.of(new BigDecimal("100.00"), Currency.MRU);

        assertThat(money.amount()).isEqualByComparingTo("100.00");
        assertThat(money.currency()).isEqualTo(Currency.MRU);
        assertThat(money.isPositive()).isTrue();
    }

    @Test
    void isPositive_withNegativeAmount_returnsFalseAndIsZeroOrPositiveAlsoFalse() {
        Money negative = Money.of(new BigDecimal("-10.00"), Currency.MRU);

        assertThat(negative.isPositive()).isFalse();
        assertThat(negative.isZeroOrPositive()).isFalse();
    }

    @Test
    void isZeroOrPositive_withZero_returnsTrueButIsPositiveReturnsFalse() {
        Money zero = Money.of(BigDecimal.ZERO, Currency.MRU);

        assertThat(zero.isPositive()).isFalse();
        assertThat(zero.isZeroOrPositive()).isTrue();
    }

    @Test
    void add_withSameCurrency_sumsAmounts() {
        Money a = Money.of(new BigDecimal("60.00"), Currency.MRU);
        Money b = Money.of(new BigDecimal("40.00"), Currency.MRU);

        assertThat(a.add(b).amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void of_withMoreThanTwoDecimals_throwsArithmeticException_noSilentRounding() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("100.123"), Currency.MRU))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void of_withNullAmount_throwsNullPointerException() {
        assertThatThrownBy(() -> Money.of(null, Currency.MRU))
                .isInstanceOf(NullPointerException.class);
    }
}
