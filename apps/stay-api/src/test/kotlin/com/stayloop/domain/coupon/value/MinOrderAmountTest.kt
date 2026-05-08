package com.stayloop.domain.coupon.value

import com.stayloop.domain.common.value.Money
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class MinOrderAmountTest {

    @DisplayName("결제 금액이 최소 결제 금액 이상이면 requireApplicable 통과.")
    @Test
    fun shouldPass_whenOrderAmountMeetsMinimum() {
        val min = MinOrderAmount(value = Money.of(10_000))

        assertThatCode { min.requireApplicable(Money.of(10_000)) }.doesNotThrowAnyException()
        assertThatCode { min.requireApplicable(Money.of(50_000)) }.doesNotThrowAnyException()
    }

    @DisplayName("결제 금액이 최소 결제 금액 미만이면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenOrderAmountBelowMinimum() {
        val min = MinOrderAmount(value = Money.of(10_000))

        assertThatThrownBy { min.requireApplicable(Money.of(9_999)) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("최소 결제 금액 자체가 0 원이면 BAD_REQUEST — 제한 없음은 부모에서 null 로 표현해야 한다.")
    @Test
    fun shouldReject_whenValueIsZero() {
        assertThatThrownBy { MinOrderAmount(value = Money.ZERO) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
