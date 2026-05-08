package com.stayloop.domain.coupon.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ExpirationPeriodTest {

    private val expiredAt = LocalDateTime.of(2026, 6, 30, 23, 59, 59)
    private val period = ExpirationPeriod(expiredAt = expiredAt)

    @DisplayName("now 가 expiredAt 이전이면 만료 X — requireUsable 통과.")
    @Test
    fun shouldPass_whenBeforeExpiry() {
        val before = expiredAt.minusSeconds(1)

        assertThat(period.isExpired(before)).isFalse()
        assertThatCode { period.requireUsable(before) }.doesNotThrowAnyException()
    }

    @DisplayName("now 가 expiredAt 과 정확히 같으면 만료로 본다 (경계 inclusive on expiry).")
    @Test
    fun shouldExpire_whenExactlyAtExpiry() {
        assertThat(period.isExpired(expiredAt)).isTrue()
        assertThatThrownBy { period.requireUsable(expiredAt) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("now 가 expiredAt 이후면 만료 — requireUsable 거절.")
    @Test
    fun shouldExpire_whenAfterExpiry() {
        val after = expiredAt.plusSeconds(1)

        assertThat(period.isExpired(after)).isTrue()
        assertThatThrownBy { period.requireUsable(after) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
