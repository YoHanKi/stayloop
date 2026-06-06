package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CancellationPolicyTest {
    @DisplayName("FREE_UNTIL 정책은 0 이상의 기준 일수를 가진다.")
    @Test
    fun shouldCreateFreeUntil() {
        assertThat(CancellationPolicy.freeUntil(7).freeUntilDaysBefore).isEqualTo(7)
    }

    @DisplayName("FREE_UNTIL 인데 기준 일수가 없으면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenFreeUntilHasNoDays() {
        assertThatThrownBy { CancellationPolicy(CancellationType.FREE_UNTIL, null) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("FREE_UNTIL 기준 일수가 음수면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenFreeUntilDaysIsNegative() {
        assertThatThrownBy { CancellationPolicy(CancellationType.FREE_UNTIL, -1) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("NON_REFUNDABLE 인데 기준 일수가 있으면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenNonRefundableHasDays() {
        assertThatThrownBy { CancellationPolicy(CancellationType.NON_REFUNDABLE, 3) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
