package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalTime

class PropertyPolicyTest {
    @DisplayName("체크인 시각이 체크아웃 시각보다 늦으면 정상 생성된다(15:00 > 11:00).")
    @Test
    fun shouldCreate_whenCheckInIsAfterCheckOut() {
        assertThatCode { PropertyPolicy.standard() }.doesNotThrowAnyException()
    }

    @DisplayName("체크인 시각이 체크아웃 시각보다 같거나 이르면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenCheckInIsNotAfterCheckOut() {
        assertThatThrownBy {
            PropertyPolicy(
                checkInTime = LocalTime.of(11, 0),
                checkOutTime = LocalTime.of(15, 0),
                cancellation = CancellationPolicy.freeUntil(7),
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("표준 정책은 체크인 15:00 / 체크아웃 11:00 / 7일 전 무료 취소다.")
    @Test
    fun shouldExposeStandardPolicy() {
        val policy = PropertyPolicy.standard()

        assertThat(policy.checkInTime).isEqualTo(LocalTime.of(15, 0))
        assertThat(policy.checkOutTime).isEqualTo(LocalTime.of(11, 0))
        assertThat(policy.cancellation.type).isEqualTo(CancellationType.FREE_UNTIL)
        assertThat(policy.cancellation.freeUntilDaysBefore).isEqualTo(7)
    }
}
