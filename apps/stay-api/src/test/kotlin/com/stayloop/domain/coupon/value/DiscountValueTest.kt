package com.stayloop.domain.coupon.value

import com.stayloop.domain.common.value.Money
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class DiscountValueTest {

    @Nested
    @DisplayName("FIXED 정액 할인")
    inner class Fixed {

        @DisplayName("FIXED 의 rawValue 가 0 이하면 BAD_REQUEST 로 거절된다 — 0원 FIXED 는 ReservationModel 불변식과 충돌해 적용 시점 BAD_REQUEST 로 늦게 폭발하므로 등록 시점에 차단.")
        @ParameterizedTest
        @ValueSource(longs = [0L, -1L, -10_000L])
        fun shouldReject_whenFixedRawValueIsZeroOrNegative(rawValue: Long) {
            assertThatThrownBy { DiscountValue(type = DiscountType.FIXED, rawValue = rawValue) }
                .isInstanceOf(CoreException::class.java)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
        }

        @DisplayName("FIXED 정액 할인 적용 시 rawValue 만큼 차감된 Money 를 반환한다.")
        @Test
        fun shouldDeductFixedAmount_whenApplied() {
            val fixed = DiscountValue(type = DiscountType.FIXED, rawValue = 3_000L)

            val deducted = fixed.apply(beforeDiscount = Money.of(10_000))

            assertThat(deducted).isEqualTo(Money.of(3_000))
        }

        @DisplayName("FIXED 가 결제 금액을 초과하면 결제 금액만큼만 차감 (cap) — 0원 결제로 떨어진다.")
        @Test
        fun shouldCapToBeforeDiscount_whenFixedExceeds() {
            val fixed = DiscountValue(type = DiscountType.FIXED, rawValue = 10_000L)

            val deducted = fixed.apply(beforeDiscount = Money.of(9_000))

            assertThat(deducted).isEqualTo(Money.of(9_000))
        }
    }

    @Nested
    @DisplayName("RATE 정률 할인")
    inner class Rate {

        @DisplayName("RATE 의 rawValue 가 1 ~ 100 범위 밖이면 BAD_REQUEST 로 거절된다.")
        @ParameterizedTest
        @ValueSource(longs = [0L, -1L, 101L, 200L])
        fun shouldReject_whenRateOutOfRange(rawValue: Long) {
            assertThatThrownBy { DiscountValue(type = DiscountType.RATE, rawValue = rawValue) }
                .isInstanceOf(CoreException::class.java)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
        }

        @DisplayName("RATE 10% 할인 적용 시 결제 금액의 10% 만큼 차감된다.")
        @Test
        fun shouldDeductPercent_whenApplied() {
            val rate = DiscountValue(type = DiscountType.RATE, rawValue = 10L)

            val deducted = rate.apply(beforeDiscount = Money.of(10_000))

            assertThat(deducted).isEqualTo(Money.of(1_000))
        }

        @DisplayName("RATE 100% 할인은 전액 차감 — 0원 결제로 떨어진다.")
        @Test
        fun shouldDeductAll_whenRateIsHundred() {
            val rate = DiscountValue(type = DiscountType.RATE, rawValue = 100L)

            val deducted = rate.apply(beforeDiscount = Money.of(9_000))

            assertThat(deducted).isEqualTo(Money.of(9_000))
        }
    }
}
