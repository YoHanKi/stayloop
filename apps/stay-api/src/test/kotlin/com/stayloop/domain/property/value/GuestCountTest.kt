package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class GuestCountTest {
    @DisplayName("기준 인원이 0 이하이면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenBaseIsZeroOrNegative() {
        assertThatThrownBy { GuestCount(base = 0, max = 2) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("최대 인원이 기준 인원보다 작으면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenMaxLessThanBase() {
        assertThatThrownBy { GuestCount(base = 4, max = 2) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("base == max 는 허용된다.")
    @Test
    fun shouldAccept_whenBaseEqualsMax() {
        val gc = GuestCount(base = 2, max = 2)

        assertThat(gc.base).isEqualTo(gc.max)
    }
}
