package com.stayloop.domain.common.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class MoneyTest {
    @DisplayName("음수 금액으로 생성 시 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenAmountIsNegative() {
        assertThatThrownBy { Money(-1L) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("0 원은 허용된다. ZERO 와 동등하다.")
    @Test
    fun shouldAllowZero() {
        assertThat(Money(0L)).isEqualTo(Money.ZERO)
        assertThat(Money.ZERO.isZero()).isTrue()
    }

    @DisplayName("plus() 는 두 Money 의 amount 를 더한다.")
    @Test
    fun shouldSumViaPlus() {
        val sum = Money(120_000L) + Money(80_000L)

        assertThat(sum).isEqualTo(Money(200_000L))
    }

    @DisplayName("times() 는 배수만큼 증가시킨다. 음수 배수는 거절된다.")
    @Test
    fun shouldMultiply() {
        assertThat(Money(120_000L) * 2).isEqualTo(Money(240_000L))

        assertThatThrownBy { Money(120_000L) * -1 }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("of() 정적 팩토리는 Money 인스턴스를 만든다.")
    @Test
    fun shouldCreateViaOf() {
        assertThat(Money.of(120_000L).amount).isEqualTo(120_000L)
    }
}
