package com.stayloop.domain.reservation

import com.stayloop.domain.reservation.value.ReservationStatus
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import com.stayloop.support.test.ReservationFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ReservationModelTest {
    @DisplayName("생성된 예약은 PENDING 이고 propertyId / roomTypeId 는 스냅샷에 위임된다.")
    @Test
    fun shouldStartAsPending() {
        val reservation = ReservationFixture.reservation()

        assertThat(reservation.status).isEqualTo(ReservationStatus.PENDING)
        assertThat(reservation.propertyId).isEqualTo(1L)
        assertThat(reservation.roomTypeId).isEqualTo(ReservationFixture.ROOM_TYPE_ID)
    }

    @DisplayName("PENDING → CONFIRMED → CHECKED_IN → CHECKED_OUT 정상 흐름을 따른다.")
    @Test
    fun shouldFollowHappyLifecycle() {
        val reservation = ReservationFixture.reservation()

        reservation.confirm()
        assertThat(reservation.status).isEqualTo(ReservationStatus.CONFIRMED)
        reservation.checkIn()
        assertThat(reservation.status).isEqualTo(ReservationStatus.CHECKED_IN)
        reservation.checkOut()
        assertThat(reservation.status).isEqualTo(ReservationStatus.CHECKED_OUT)
    }

    @DisplayName("취소하면 CANCELLED 가 되고 cancelledAt 이 기록된다.")
    @Test
    fun shouldRecordCancelledAt() {
        val reservation = ReservationFixture.reservation()
        val now = LocalDateTime.of(2026, 6, 1, 9, 0)

        reservation.cancel(now)

        assertThat(reservation.status).isEqualTo(ReservationStatus.CANCELLED)
        assertThat(reservation.cancelledAt).isEqualTo(now)
    }

    @DisplayName("CHECKED_IN 이후에는 취소할 수 없어 CONFLICT 로 거절된다.")
    @Test
    fun shouldReject_whenCancelAfterCheckIn() {
        val reservation = ReservationFixture.reservation()
        reservation.confirm()
        reservation.checkIn()

        assertThatThrownBy { reservation.cancel(LocalDateTime.now()) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }
}
