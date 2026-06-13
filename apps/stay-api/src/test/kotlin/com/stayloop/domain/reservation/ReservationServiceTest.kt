package com.stayloop.domain.reservation

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.reservation.value.ReservationStatus
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import com.stayloop.support.test.ReservationFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReservationServiceTest {
    private val service = ReservationService(ReservationPriceCalculator())

    private val day1 = LocalDate.of(2026, 6, 1)
    private val day2 = LocalDate.of(2026, 6, 2)

    private fun reserve(
        guestCount: Int = 2,
        inventories: List<com.stayloop.domain.inventory.DailyRoomInventoryModel> =
            listOf(ReservationFixture.inventory(day1), ReservationFixture.inventory(day2)),
        rates: List<com.stayloop.domain.rate.DailyRoomRateModel> =
            listOf(ReservationFixture.rate(day1, 100_000), ReservationFixture.rate(day2, 120_000)),
    ) = service.reserve(
        userId = ReservationFixture.USER,
        property = ReservationFixture.property(),
        roomType = ReservationFixture.roomType(),
        period = ReservationFixture.period(),
        guestCount = guestCount,
        guest = ReservationFixture.GUEST,
        inventories = inventories,
        rates = rates,
    )

    @DisplayName("정상 예약은 PENDING 으로 생성되고 합산 요금이 매겨지며 날짜별 재고가 1 씩 차감된다.")
    @Test
    fun shouldReserveNormally() {
        val inventories = listOf(ReservationFixture.inventory(day1), ReservationFixture.inventory(day2))

        val reservation = reserve(inventories = inventories)

        assertThat(reservation.status).isEqualTo(ReservationStatus.PENDING)
        assertThat(reservation.totalPrice).isEqualTo(Money.of(220_000))
        assertThat(inventories.map { it.reservedRooms }).containsExactly(1, 1)
    }

    @DisplayName("요청 인원이 객실 최대 인원을 넘으면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenGuestExceedsMax() {
        assertThatThrownBy { reserve(guestCount = 5) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("어느 한 날짜라도 재고가 없으면 CONFLICT 로 거절된다(AC-4 의 도메인 1차 가드).")
    @Test
    fun shouldReject_whenSoldOut() {
        val inventories = listOf(
            ReservationFixture.inventory(day1, total = 1, reserved = 1),
            ReservationFixture.inventory(day2),
        )

        assertThatThrownBy { reserve(inventories = inventories) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("재고·요금이 투숙 일자와 1:1 로 맞지 않으면(누락) BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenDatesMismatch() {
        val inventories = listOf(ReservationFixture.inventory(day1))

        assertThatThrownBy { reserve(inventories = inventories) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("취소는 예약을 CANCELLED 로 바꾸고 날짜별 재고를 복원한다.")
    @Test
    fun shouldCancelAndRestoreInventory() {
        val inventories = listOf(
            ReservationFixture.inventory(day1, total = 2, reserved = 1),
            ReservationFixture.inventory(day2, total = 2, reserved = 1),
        )
        val reservation = ReservationFixture.reservation()

        service.cancel(reservation, inventories, LocalDateTime.of(2026, 5, 30, 9, 0))

        assertThat(reservation.status).isEqualTo(ReservationStatus.CANCELLED)
        assertThat(inventories.map { it.reservedRooms }).containsExactly(0, 0)
    }

    @DisplayName("CHECKED_IN 이후 취소는 CONFLICT 로 거절되고 재고도 건드리지 않는다.")
    @Test
    fun shouldReject_whenCancelAfterCheckIn() {
        val inventories = listOf(ReservationFixture.inventory(day1, total = 2, reserved = 1))
        val reservation = ReservationFixture.reservation()
        reservation.confirm()
        reservation.checkIn()

        assertThatThrownBy { service.cancel(reservation, inventories, LocalDateTime.now()) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
        assertThat(inventories.single().reservedRooms).isEqualTo(1)
    }
}
