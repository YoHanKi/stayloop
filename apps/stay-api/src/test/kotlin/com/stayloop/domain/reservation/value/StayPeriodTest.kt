package com.stayloop.domain.reservation.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

class StayPeriodTest {
    @DisplayName("체크아웃이 체크인보다 같거나 이르면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenCheckOutNotAfterCheckIn() {
        val day = LocalDate.of(2026, 6, 1)
        assertThatThrownBy { StayPeriod(checkIn = day, checkOut = day) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("nights 는 체크인~체크아웃 사이의 박 수다.")
    @Test
    fun shouldComputeNights() {
        val period = StayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 4))

        assertThat(period.nights()).isEqualTo(3)
    }

    @DisplayName("datesToReserve 는 체크아웃 당일을 제외한 날짜 목록이다([from, to)).")
    @Test
    fun shouldExcludeCheckoutDate() {
        val period = StayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 4))

        assertThat(period.datesToReserve()).containsExactly(
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 2),
            LocalDate.of(2026, 6, 3),
        )
    }
}
