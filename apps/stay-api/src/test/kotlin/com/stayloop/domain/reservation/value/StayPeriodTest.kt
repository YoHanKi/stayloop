package com.stayloop.domain.reservation.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

class StayPeriodTest {

    @DisplayName("1박 정상 — checkOut 이 checkIn 의 다음 날이면 nights=1, datesToReserve=[checkIn] 한 개.")
    @Test
    fun shouldReturnOneNight_whenCheckOutIsNextDay() {
        val period = StayPeriod(
            checkIn = LocalDate.of(2026, 5, 10),
            checkOut = LocalDate.of(2026, 5, 11),
        )

        assertThat(period.nights()).isEqualTo(1)
        assertThat(period.datesToReserve()).containsExactly(LocalDate.of(2026, 5, 10))
    }

    @DisplayName("3박 정상 — datesToReserve 는 체크아웃 당일을 제외한 [checkIn, checkOut) 반-닫힌 구간.")
    @Test
    fun shouldReturnDatesInHalfOpenRange_whenMultipleNights() {
        val period = StayPeriod(
            checkIn = LocalDate.of(2026, 5, 10),
            checkOut = LocalDate.of(2026, 5, 13),
        )

        assertThat(period.nights()).isEqualTo(3)
        assertThat(period.datesToReserve()).containsExactly(
            LocalDate.of(2026, 5, 10),
            LocalDate.of(2026, 5, 11),
            LocalDate.of(2026, 5, 12),
        )
    }

    @DisplayName("nights() 와 datesToReserve().size 는 항상 일치한다 — Inventory 차감 횟수의 일관성 보장.")
    @Test
    fun shouldKeepNightsAndDatesSizeConsistent() {
        val period = StayPeriod(
            checkIn = LocalDate.of(2026, 12, 24),
            checkOut = LocalDate.of(2026, 12, 30),
        )

        assertThat(period.datesToReserve()).hasSize(period.nights())
    }

    @DisplayName("checkOut 이 checkIn 과 같으면(0박) BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenCheckOutEqualsCheckIn() {
        val sameDay = LocalDate.of(2026, 5, 10)

        assertThatThrownBy { StayPeriod(checkIn = sameDay, checkOut = sameDay) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("checkOut 이 checkIn 보다 앞이면(음수 박) BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenCheckOutBeforeCheckIn() {
        assertThatThrownBy {
            StayPeriod(
                checkIn = LocalDate.of(2026, 5, 10),
                checkOut = LocalDate.of(2026, 5, 9),
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
