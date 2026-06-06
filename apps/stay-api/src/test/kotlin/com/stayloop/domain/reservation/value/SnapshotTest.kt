package com.stayloop.domain.reservation.value

import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.RoomTypeModel
import com.stayloop.domain.property.value.Address
import com.stayloop.domain.property.value.BedConfig
import com.stayloop.domain.property.value.BedType
import com.stayloop.domain.property.value.GuestCount
import com.stayloop.domain.property.value.PropertyCategory
import com.stayloop.domain.property.value.PropertyName
import com.stayloop.domain.property.value.PropertyPolicy
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import com.stayloop.support.test.InMemoryPropertyRepository
import com.stayloop.support.test.InMemoryRoomTypeRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SnapshotTest {
    @DisplayName("PropertySnapshot.from 은 예약 시점의 숙소 식별자·이름·카테고리·도시를 박제한다.")
    @Test
    fun shouldSnapshotProperty() {
        val saved = InMemoryPropertyRepository().save(
            PropertyModel.create(
                name = PropertyName("스테이루프 호텔"),
                category = PropertyCategory.HOTEL,
                address = Address("seoul", "서울특별시 중구 세종대로 110"),
                policy = PropertyPolicy.standard(),
            ),
        )

        val snapshot = PropertySnapshot.from(saved)

        assertThat(snapshot.propertyId).isEqualTo(saved.id)
        assertThat(snapshot.name).isEqualTo("스테이루프 호텔")
        assertThat(snapshot.category).isEqualTo(PropertyCategory.HOTEL)
        assertThat(snapshot.city).isEqualTo("seoul")
    }

    @DisplayName("RoomTypeSnapshot.from 은 식별자·이름·인원을 박제하고 checkGuestCount 가 범위를 검증한다.")
    @Test
    fun shouldSnapshotRoomTypeAndCheckGuests() {
        val saved = InMemoryRoomTypeRepository().save(
            RoomTypeModel.create(
                propertyId = 1L,
                name = "디럭스 더블",
                guestCount = GuestCount(baseGuests = 2, maxGuests = 4),
                bedConfig = BedConfig(mapOf(BedType.DOUBLE to 1)),
            ),
        )

        val snapshot = RoomTypeSnapshot.from(saved)

        assertThat(snapshot.roomTypeId).isEqualTo(saved.id)
        assertThat(snapshot.maxGuests).isEqualTo(4)
        assertThatThrownBy { snapshot.checkGuestCount(5) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
