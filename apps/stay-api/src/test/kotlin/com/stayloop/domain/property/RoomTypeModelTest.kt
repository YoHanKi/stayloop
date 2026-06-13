package com.stayloop.domain.property

import com.stayloop.domain.property.value.BedConfig
import com.stayloop.domain.property.value.BedType
import com.stayloop.domain.property.value.GuestCount
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class RoomTypeModelTest {
    private fun newRoomType(base: Int = 2, max: Int = 4): RoomTypeModel =
        RoomTypeModel.create(
            propertyId = 1L,
            name = "디럭스 더블",
            guestCount = GuestCount(baseGuests = base, maxGuests = max),
            bedConfig = BedConfig(mapOf(BedType.DOUBLE to 1)),
        )

    @DisplayName("요청 인원이 수용 범위 안이면 통과한다.")
    @Test
    fun shouldPass_whenGuestWithinRange() {
        assertThatCode { newRoomType().checkGuestCount(4) }.doesNotThrowAnyException()
    }

    @DisplayName("요청 인원이 0 이하면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenGuestIsZero() {
        assertThatThrownBy { newRoomType().checkGuestCount(0) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("요청 인원이 최대 인원을 넘으면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenGuestExceedsMax() {
        assertThatThrownBy { newRoomType().checkGuestCount(5) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("이름이 공백이면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenNameBlank() {
        assertThatThrownBy {
            RoomTypeModel.create(
                propertyId = 1L,
                name = " ",
                guestCount = GuestCount(2, 4),
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
