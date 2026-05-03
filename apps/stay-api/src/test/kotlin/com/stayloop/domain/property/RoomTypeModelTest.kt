package com.stayloop.domain.property

import com.stayloop.domain.property.value.BedConfig
import com.stayloop.domain.property.value.BedType
import com.stayloop.domain.property.value.GuestCount
import com.stayloop.domain.property.value.Name
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class RoomTypeModelTest {

    private fun newRoomType(maxGuests: Int = 2): RoomTypeModel = RoomTypeModel.create(
        propertyId = 1024L,
        name = Name("스탠다드 더블"),
        guestCount = GuestCount(base = 2, max = maxGuests),
        bedConfig = BedConfig.of(BedType.DOUBLE to 1),
    )

    @DisplayName("propertyId 가 0 이하이면 BAD_REQUEST 로 거절된다 — 영속화되지 않은 Property 참조 차단.")
    @Test
    fun shouldReject_whenPropertyIdIsZeroOrNegative() {
        assertThatThrownBy {
            RoomTypeModel.create(
                propertyId = 0L,
                name = Name("스탠다드 더블"),
                guestCount = GuestCount(base = 2, max = 2),
                bedConfig = BedConfig.of(BedType.DOUBLE to 1),
            )
        }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)

        assertThatThrownBy {
            RoomTypeModel.create(
                propertyId = -1L,
                name = Name("스탠다드 더블"),
                guestCount = GuestCount(base = 2, max = 2),
                bedConfig = BedConfig.of(BedType.DOUBLE to 1),
            )
        }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("checkGuestCount(0) 은 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenZero() {
        val roomType = newRoomType()

        assertThatThrownBy { roomType.checkGuestCount(0) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("AC-5 — 요청 인원이 max 를 초과하면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenExceedsMax() {
        val roomType = newRoomType(maxGuests = 2)

        assertThatThrownBy { roomType.checkGuestCount(3) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("요청 인원이 max 이하이면 통과한다.")
    @Test
    fun shouldPass_whenWithinMax() {
        val roomType = newRoomType(maxGuests = 4)

        assertThatCode { roomType.checkGuestCount(4) }.doesNotThrowAnyException()
        assertThatCode { roomType.checkGuestCount(1) }.doesNotThrowAnyException()
    }
}
