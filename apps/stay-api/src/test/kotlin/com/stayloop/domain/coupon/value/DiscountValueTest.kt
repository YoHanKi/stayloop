package com.stayloop.domain.coupon.value

import com.stayloop.domain.common.value.Money
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class DiscountValueTest {
    @DisplayName("정액 할인은 주문 금액에서 정액만큼 깎고, 정액이 주문 금액을 넘으면 주문 금액까지만 깎는다.")
    @Test
    fun fixedDiscount() {
        assertThat(DiscountValue.of(DiscountType.FIXED, 5_000).discount(Money.of(20_000))).isEqualTo(Money.of(5_000))
        assertThat(DiscountValue.of(DiscountType.FIXED, 30_000).discount(Money.of(20_000))).isEqualTo(Money.of(20_000))
    }

    @DisplayName("정률 할인은 주문 금액의 비율만큼 깎는다.")
    @Test
    fun percentDiscount() {
        assertThat(DiscountValue.of(DiscountType.PERCENT, 10).discount(Money.of(100_000))).isEqualTo(Money.of(10_000))
    }

    @DisplayName("정액 할인 금액이 0 이하면 BAD_REQUEST 로 거절된다.")
    @Test
    fun rejectFixedNonPositive() {
        assertThatThrownBy { DiscountValue.of(DiscountType.FIXED, 0) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("정률 할인율이 1~100 밖이면 BAD_REQUEST 로 거절된다.")
    @Test
    fun rejectPercentOutOfRange() {
        assertThatThrownBy { DiscountValue.of(DiscountType.PERCENT, 0) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
        assertThatThrownBy { DiscountValue.of(DiscountType.PERCENT, 101) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
