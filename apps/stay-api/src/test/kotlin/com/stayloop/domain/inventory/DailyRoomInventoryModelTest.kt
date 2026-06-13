package com.stayloop.domain.inventory

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

class DailyRoomInventoryModelTest {
    private fun inventory(total: Int = 2, reserved: Int = 0): DailyRoomInventoryModel =
        DailyRoomInventoryModel(roomTypeId = 1L, date = LocalDate.of(2026, 6, 6), totalRooms = total, reservedRooms = reserved)

    @DisplayName("available 은 총 객실에서 예약 객실을 뺀 값이다.")
    @Test
    fun shouldComputeAvailable() {
        assertThat(inventory(total = 5, reserved = 2).available()).isEqualTo(3)
    }

    @DisplayName("reserveOne 은 예약 객실을 1 늘리고, 남은 객실이 없으면 CONFLICT 로 거절된다.")
    @Test
    fun shouldReserveOne_untilSoldOut() {
        val inventory = inventory(total = 1, reserved = 0)

        inventory.reserveOne()
        assertThat(inventory.available()).isEqualTo(0)

        assertThatThrownBy { inventory.reserveOne() }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("releaseOne 은 예약 객실을 1 줄이고, 예약이 없으면 BAD_REQUEST 로 거절된다(음수 복원 방지).")
    @Test
    fun shouldReleaseOne_untilZero() {
        val inventory = inventory(total = 2, reserved = 1)

        inventory.releaseOne()
        assertThat(inventory.reservedRooms).isEqualTo(0)

        assertThatThrownBy { inventory.releaseOne() }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("예약 객실 수가 총 객실 수를 넘는 상태로는 생성할 수 없다.")
    @Test
    fun shouldReject_whenReservedExceedsTotal() {
        assertThatThrownBy { inventory(total = 1, reserved = 2) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
