package com.stayloop.domain.reservation

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.rate.ReservationPriceCalculator
import com.stayloop.domain.reservation.value.GuestInfo
import com.stayloop.domain.reservation.value.PropertySnapshot
import com.stayloop.domain.reservation.value.ReservationStatus
import com.stayloop.domain.reservation.value.RoomTypeSnapshot
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReservationServiceTest {
    private val service = ReservationService(ReservationPriceCalculator())
    private val anyUser = LoginId("alpha01")
    private val anyGuest = GuestInfo(name = "홍길동", phoneNumber = PhoneNumber("010-1234-5678"))

    private val standardPeriod = StayPeriod(
        checkIn = LocalDate.of(2026, 5, 10),
        checkOut = LocalDate.of(2026, 5, 12),
    ) // 5/10, 5/11 — 2박
    private val standardSnapshotProperty = PropertySnapshot(
        propertyId = 7L,
        propertyName = "Stayloop 호텔",
        propertyAddress = "서울 강남구",
    )
    private val standardSnapshotRoomType = RoomTypeSnapshot(
        roomTypeId = 11L,
        roomTypeName = "디럭스 더블",
        maxGuests = 4,
    )

    @DisplayName("정상 reserve — inventory 가 차감되고, 합산가가 rates 합과 일치하며, status 는 PENDING.")
    @Test
    fun shouldReserveAndComputeTotal() {
        val inventories = listOf(
            inventoryAt(LocalDate.of(2026, 5, 10), totalRooms = 5),
            inventoryAt(LocalDate.of(2026, 5, 11), totalRooms = 5),
        )
        val rates = listOf(
            rateAt(LocalDate.of(2026, 5, 10), price = 100_000L),
            rateAt(LocalDate.of(2026, 5, 11), price = 110_000L),
        )

        val (changedInventories, reservation) = service.reserve(
            userId = anyUser,
            propertySnapshot = standardSnapshotProperty,
            roomTypeSnapshot = standardSnapshotRoomType,
            period = standardPeriod,
            guestCount = 2,
            guest = anyGuest,
            inventories = inventories,
            rates = rates,
        )

        assertThat(changedInventories.map { it.reservedRooms }).containsExactly(1, 1)
        assertThat(reservation.totalPrice).isEqualTo(Money.of(210_000L))
        assertThat(reservation.status).isEqualTo(ReservationStatus.PENDING)
    }

    @DisplayName("guestCount 가 maxGuests 를 초과하면 BAD_REQUEST — 인원 초과 거절(AC-5).")
    @Test
    fun shouldReject_whenGuestCountExceedsMax() {
        val inventories = listOf(
            inventoryAt(LocalDate.of(2026, 5, 10), totalRooms = 5),
            inventoryAt(LocalDate.of(2026, 5, 11), totalRooms = 5),
        )
        val rates = listOf(
            rateAt(LocalDate.of(2026, 5, 10), price = 100_000L),
            rateAt(LocalDate.of(2026, 5, 11), price = 110_000L),
        )

        assertThatThrownBy {
            service.reserve(
                userId = anyUser,
                propertySnapshot = standardSnapshotProperty,
                roomTypeSnapshot = standardSnapshotRoomType,
                period = standardPeriod,
                // maxGuests=4 초과 (5명)
                guestCount = 5,
                guest = anyGuest,
                inventories = inventories,
                rates = rates,
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("inventories 가 비어 있으면 BAD_REQUEST — 일자 누락 검증.")
    @Test
    fun shouldReject_whenInventoriesEmpty() {
        assertThatThrownBy {
            service.reserve(
                userId = anyUser,
                propertySnapshot = standardSnapshotProperty,
                roomTypeSnapshot = standardSnapshotRoomType,
                period = standardPeriod,
                guestCount = 2,
                guest = anyGuest,
                inventories = emptyList(),
                rates = listOf(rateAt(LocalDate.of(2026, 5, 10), 100_000L), rateAt(LocalDate.of(2026, 5, 11), 100_000L)),
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("inventories 가 일부 일자만 있으면(누락) BAD_REQUEST — datesToReserve 와 set 동치 X.")
    @Test
    fun shouldReject_whenInventoriesMissingDate() {
        val partial = listOf(inventoryAt(LocalDate.of(2026, 5, 10), totalRooms = 5)) // 5/11 누락
        val rates = listOf(
            rateAt(LocalDate.of(2026, 5, 10), 100_000L),
            rateAt(LocalDate.of(2026, 5, 11), 100_000L),
        )

        assertThatThrownBy {
            service.reserve(
                userId = anyUser,
                propertySnapshot = standardSnapshotProperty,
                roomTypeSnapshot = standardSnapshotRoomType,
                period = standardPeriod,
                guestCount = 2,
                guest = anyGuest,
                inventories = partial,
                rates = rates,
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("rates 가 비어 있으면 BAD_REQUEST — 요금 일자 누락.")
    @Test
    fun shouldReject_whenRatesEmpty() {
        val inventories = listOf(
            inventoryAt(LocalDate.of(2026, 5, 10), totalRooms = 5),
            inventoryAt(LocalDate.of(2026, 5, 11), totalRooms = 5),
        )

        assertThatThrownBy {
            service.reserve(
                userId = anyUser,
                propertySnapshot = standardSnapshotProperty,
                roomTypeSnapshot = standardSnapshotRoomType,
                period = standardPeriod,
                guestCount = 2,
                guest = anyGuest,
                inventories = inventories,
                rates = emptyList(),
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("inventory 의 가용 0 이면 reserveOne() 이 CONFLICT — 재고 부족 거절.")
    @Test
    fun shouldReject_whenInventoryUnavailable() {
        // 5/10 가용 0 (totalRooms=1, reservedRooms=1) — reserveOne 시 CONFLICT
        val inventories = listOf(
            inventoryAt(LocalDate.of(2026, 5, 10), totalRooms = 1, reservedRooms = 1),
            inventoryAt(LocalDate.of(2026, 5, 11), totalRooms = 5),
        )
        val rates = listOf(
            rateAt(LocalDate.of(2026, 5, 10), 100_000L),
            rateAt(LocalDate.of(2026, 5, 11), 100_000L),
        )

        assertThatThrownBy {
            service.reserve(
                userId = anyUser,
                propertySnapshot = standardSnapshotProperty,
                roomTypeSnapshot = standardSnapshotRoomType,
                period = standardPeriod,
                guestCount = 2,
                guest = anyGuest,
                inventories = inventories,
                rates = rates,
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("정상 cancel — reservation status=CANCELLED, cancelledAt 박제, inventory reservedRooms 가 모두 -1.")
    @Test
    fun shouldCancelAndRestoreInventory() {
        val inventories = listOf(
            inventoryAt(LocalDate.of(2026, 5, 10), totalRooms = 5, reservedRooms = 1),
            inventoryAt(LocalDate.of(2026, 5, 11), totalRooms = 5, reservedRooms = 2),
        )
        val rates = listOf(
            rateAt(LocalDate.of(2026, 5, 10), 100_000L),
            rateAt(LocalDate.of(2026, 5, 11), 110_000L),
        )
        // PENDING 상태 reservation 생성 (reserve 흐름이 1 차감하므로 inventory 들은 새로 만들고 reserve 후 cancel)
        val (afterReserve, reservation) = service.reserve(
            userId = anyUser,
            propertySnapshot = standardSnapshotProperty,
            roomTypeSnapshot = standardSnapshotRoomType,
            period = standardPeriod,
            guestCount = 2,
            guest = anyGuest,
            inventories = inventories,
            rates = rates,
        )
        // reserve 후 reservedRooms: 5/10 → 2, 5/11 → 3
        assertThat(afterReserve.map { it.reservedRooms }).containsExactly(2, 3)

        val cancelTime = LocalDateTime.of(2026, 5, 1, 12, 0)
        val (afterCancel, cancelled) = service.cancel(reservation, afterReserve, cancelTime)

        assertThat(cancelled.status).isEqualTo(ReservationStatus.CANCELLED)
        assertThat(cancelled.cancelledAt).isEqualTo(cancelTime)
        assertThat(afterCancel.map { it.reservedRooms }).containsExactly(1, 2)
    }

    @DisplayName("CHECKED_IN 이후 cancel 호출은 reservation.cancel 이 CONFLICT — Service 도 그대로 전파.")
    @Test
    fun shouldReject_cancelAfterCheckedIn() {
        val inventories = listOf(
            inventoryAt(LocalDate.of(2026, 5, 10), totalRooms = 5),
            inventoryAt(LocalDate.of(2026, 5, 11), totalRooms = 5),
        )
        val rates = listOf(
            rateAt(LocalDate.of(2026, 5, 10), 100_000L),
            rateAt(LocalDate.of(2026, 5, 11), 100_000L),
        )
        val (_, reservation) = service.reserve(
            userId = anyUser,
            propertySnapshot = standardSnapshotProperty,
            roomTypeSnapshot = standardSnapshotRoomType,
            period = standardPeriod,
            guestCount = 2,
            guest = anyGuest,
            inventories = inventories,
            rates = rates,
        )
        reservation.confirm()
        reservation.checkIn() // status = CHECKED_IN

        assertThatThrownBy {
            service.cancel(reservation, inventories, LocalDateTime.of(2026, 5, 11, 10, 0))
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    private fun inventoryAt(date: LocalDate, totalRooms: Int, reservedRooms: Int = 0): DailyRoomInventoryModel =
        DailyRoomInventoryModel.create(
            roomTypeId = 11L,
            date = date,
            totalRooms = totalRooms,
            reservedRooms = reservedRooms,
        )

    private fun rateAt(date: LocalDate, price: Long): DailyRoomRateModel =
        DailyRoomRateModel.create(
            roomTypeId = 11L,
            date = date,
            pricePerNight = Money.of(price),
        )
}
