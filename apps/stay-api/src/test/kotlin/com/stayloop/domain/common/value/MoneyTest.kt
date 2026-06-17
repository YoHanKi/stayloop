package com.stayloop.domain.common.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyTest {
    @DisplayName("음수 금액으로 생성하면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenAmountIsNegative() {
        assertThatThrownBy { Money.of(BigDecimal("-1")) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("0원은 정상 생성되고 isZero 가 참이다.")
    @Test
    fun shouldCreateZero() {
        assertThat(Money.of(0).isZero()).isTrue()
        assertThat(Money.ZERO.isZero()).isTrue()
    }

    @DisplayName("두 금액을 더하면 합산된 금액이 된다.")
    @Test
    fun shouldAddTwoAmounts() {
        assertThat(Money.of(1000) + Money.of(2500)).isEqualTo(Money.of(3500))
    }

    @DisplayName("scale 이 달라도(1000 vs 1000.00) 같은 금액으로 취급한다.")
    @Test
    fun shouldEqual_whenScaleDiffers() {
        val a = Money.of(BigDecimal("1000"))
        val b = Money.of(BigDecimal("1000.00"))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @DisplayName("소수 곱셈은 scale 2 로 반올림(HALF_UP)한다.")
    @Test
    fun shouldRoundMultiplication() {
        // 1000 * 0.075 = 75.0 → 75.00
        assertThat(Money.of(1000) * BigDecimal("0.075")).isEqualTo(Money.of(75))
        // 1001 * 0.005 = 5.005 → 5.01 (HALF_UP)
        assertThat(Money.of(1001) * BigDecimal("0.005")).isEqualTo(Money.of(BigDecimal("5.01")))
    }

    @DisplayName("compareTo 로 금액 대소를 비교한다.")
    @Test
    fun shouldCompareByAmount() {
        assertThat(Money.of(1000) < Money.of(2000)).isTrue()
        assertThat(Money.of(2000) > Money.of(1000)).isTrue()
    }

    @DisplayName("두 금액을 빼면 차액이 된다.")
    @Test
    fun shouldSubtract() {
        assertThat(Money.of(220_000) - Money.of(20_000)).isEqualTo(Money.of(200_000))
    }

    @DisplayName("차감 결과가 음수면 BAD_REQUEST 로 거절된다(할인이 원금 초과).")
    @Test
    fun shouldReject_whenSubtractionGoesNegative() {
        assertThatThrownBy { Money.of(10_000) - Money.of(20_000) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
