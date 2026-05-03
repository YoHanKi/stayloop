package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalTime

class PropertyPolicyTest {
    @DisplayName("정상 정책은 체크인(15:00) > 체크아웃(11:00) 이다.")
    @Test
    fun shouldBuildValidPolicy() {
        val policy = PropertyPolicy(
            checkInTime = LocalTime.of(15, 0),
            checkOutTime = LocalTime.of(11, 0),
            cancellation = CancellationPolicy(CancellationType.FREE_UNTIL, freeUntilDaysBefore = 3),
            smokingAllowed = false,
            petAllowed = false,
        )

        assertThat(policy.cancellation.freeUntilDaysBefore).isEqualTo(3)
    }

    @DisplayName("체크인 시각이 체크아웃 시각보다 이르거나 같으면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenCheckInIsNotLater() {
        assertThatThrownBy {
            PropertyPolicy(
                checkInTime = LocalTime.of(10, 0),
                checkOutTime = LocalTime.of(11, 0),
                cancellation = CancellationPolicy(CancellationType.NON_REFUNDABLE),
            )
        }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}

class CancellationPolicyTest {
    @DisplayName("freeUntilDaysBefore 음수는 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenNegative() {
        assertThatThrownBy { CancellationPolicy(CancellationType.FREE_UNTIL, freeUntilDaysBefore = -1) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("환불 불가 정책에서 freeUntilDaysBefore != 0 이면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenNonRefundableHasFreeDays() {
        assertThatThrownBy { CancellationPolicy(CancellationType.NON_REFUNDABLE, freeUntilDaysBefore = 3) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
