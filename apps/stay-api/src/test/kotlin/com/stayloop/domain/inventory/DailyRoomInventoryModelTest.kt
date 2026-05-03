package com.stayloop.domain.inventory

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate

class DailyRoomInventoryModelTest {
    private val anyDate = LocalDate.of(2026, 5, 10)

    @DisplayName("reservedRooms 가 totalRooms 를 초과하면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenReservedExceedsTotal() {
        assertThatThrownBy {
            DailyRoomInventoryModel.create(roomTypeId = 1L, date = anyDate, totalRooms = 3, reservedRooms = 4)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("totalRooms 가 0 이하(0 / 음수) 이면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(ints = [0, -1, -100])
    fun shouldReject_whenTotalIsZeroOrNegative(totalRooms: Int) {
        assertThatThrownBy {
            DailyRoomInventoryModel.create(roomTypeId = 1L, date = anyDate, totalRooms = totalRooms)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("reservedRooms 가 음수이면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(ints = [-1, -100])
    fun shouldReject_whenReservedIsNegative(reservedRooms: Int) {
        assertThatThrownBy {
            DailyRoomInventoryModel.create(roomTypeId = 1L, date = anyDate, totalRooms = 5, reservedRooms = reservedRooms)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("roomTypeId 가 0 이하(0 / 음수) 이면 BAD_REQUEST 로 거절된다 — 영속화된 RoomType 참조 필수.")
    @ParameterizedTest
    @ValueSource(longs = [0L, -1L, -100L])
    fun shouldReject_whenRoomTypeIdIsZeroOrNegative(roomTypeId: Long) {
        assertThatThrownBy {
            DailyRoomInventoryModel.create(roomTypeId = roomTypeId, date = anyDate, totalRooms = 5)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("available() 은 totalRooms - reservedRooms 로 계산된다.")
    @Test
    fun shouldComputeAvailableAsTotalMinusReserved() {
        val inventory = DailyRoomInventoryModel.create(roomTypeId = 1L, date = anyDate, totalRooms = 5, reservedRooms = 2)
        assertThat(inventory.available()).isEqualTo(3)
    }

    @DisplayName("reserveOne() 은 가용 객실이 있으면 reservedRooms 를 1 증가시킨다.")
    @Test
    fun shouldIncrementReserved_whenAvailable() {
        val inventory = DailyRoomInventoryModel.create(roomTypeId = 1L, date = anyDate, totalRooms = 5)
        inventory.reserveOne()
        inventory.reserveOne()
        assertThat(inventory.reservedRooms).isEqualTo(2)
        assertThat(inventory.available()).isEqualTo(3)
    }

    @DisplayName("reserveOne() 은 가용 객실이 0 이면 CONFLICT 로 거절한다 — 더블부킹 도메인 가드.")
    @Test
    fun shouldReject_whenReservingFullInventory() {
        val inventory = DailyRoomInventoryModel.create(roomTypeId = 1L, date = anyDate, totalRooms = 1, reservedRooms = 1)
        assertThatThrownBy { inventory.reserveOne() }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("releaseOne() 은 예약된 객실이 있을 때 reservedRooms 를 1 감소시킨다.")
    @Test
    fun shouldDecrementReserved_whenReleasing() {
        val inventory = DailyRoomInventoryModel.create(roomTypeId = 1L, date = anyDate, totalRooms = 5, reservedRooms = 2)
        inventory.releaseOne()
        assertThat(inventory.reservedRooms).isEqualTo(1)
        assertThat(inventory.available()).isEqualTo(4)
    }

    @DisplayName("releaseOne() 은 예약 0 일 때 CONFLICT 로 거절한다 — 음수 진입 차단.")
    @Test
    fun shouldReject_whenReleasingEmptyInventory() {
        val inventory = DailyRoomInventoryModel.create(roomTypeId = 1L, date = anyDate, totalRooms = 5, reservedRooms = 0)
        assertThatThrownBy { inventory.releaseOne() }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("reserveOne / releaseOne 왕복 후 reservedRooms / available 이 원래 값으로 복원된다.")
    @Test
    fun shouldRoundTrip_reserveAndRelease() {
        val inventory = DailyRoomInventoryModel.create(roomTypeId = 1L, date = anyDate, totalRooms = 5, reservedRooms = 2)
        inventory.reserveOne()
        inventory.releaseOne()
        assertThat(inventory.reservedRooms).isEqualTo(2)
        assertThat(inventory.available()).isEqualTo(3)
    }
}
