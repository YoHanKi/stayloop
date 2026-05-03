package com.stayloop.domain.reservation.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RoomTypeSnapshotTest {

    @DisplayName("정상 생성 시 박제된 roomTypeId / roomTypeName / maxGuests 가 그대로 노출된다.")
    @Test
    fun shouldExposeFields() {
        val snapshot = RoomTypeSnapshot(roomTypeId = 1L, roomTypeName = "디럭스 더블", maxGuests = 4)

        assertThat(snapshot.roomTypeId).isEqualTo(1L)
        assertThat(snapshot.roomTypeName).isEqualTo("디럭스 더블")
        assertThat(snapshot.maxGuests).isEqualTo(4)
    }

    @DisplayName("roomTypeId 가 0 이하면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun shouldReject_whenRoomTypeIdIsZeroOrNegative(roomTypeId: Long) {
        assertThatThrownBy {
            RoomTypeSnapshot(roomTypeId = roomTypeId, roomTypeName = "이름", maxGuests = 2)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("roomTypeName 이 빈 문자열이거나 MAX_NAME_LENGTH(100) 초과면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenRoomTypeNameViolatesGuard() {
        assertThatThrownBy {
            RoomTypeSnapshot(roomTypeId = 1L, roomTypeName = "", maxGuests = 2)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)

        val tooLong = "가".repeat(RoomTypeSnapshot.MAX_NAME_LENGTH + 1)
        assertThatThrownBy {
            RoomTypeSnapshot(roomTypeId = 1L, roomTypeName = tooLong, maxGuests = 2)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("maxGuests 가 0 이하면 BAD_REQUEST 로 거절된다 — 인원 검증 의미상 양수 보장.")
    @ParameterizedTest
    @ValueSource(ints = [0, -1, -100])
    fun shouldReject_whenMaxGuestsIsZeroOrNegative(maxGuests: Int) {
        assertThatThrownBy {
            RoomTypeSnapshot(roomTypeId = 1L, roomTypeName = "이름", maxGuests = maxGuests)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
