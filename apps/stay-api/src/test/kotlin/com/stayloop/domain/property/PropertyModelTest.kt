package com.stayloop.domain.property

import com.stayloop.domain.property.value.Address
import com.stayloop.domain.property.value.Amenities
import com.stayloop.domain.property.value.AmenityTag
import com.stayloop.domain.property.value.CancellationPolicy
import com.stayloop.domain.property.value.CancellationType
import com.stayloop.domain.property.value.Name
import com.stayloop.domain.property.value.PropertyCategory
import com.stayloop.domain.property.value.PropertyPolicy
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalTime

class PropertyModelTest {

    private fun newProperty(): PropertyModel = PropertyModel.create(
        name = Name("강남 라마다 호텔"),
        category = PropertyCategory.HOTEL,
        description = "강남 한복판",
        address = Address(city = "seoul", fullAddress = "강남구 테헤란로 1"),
        amenities = Amenities.of(AmenityTag.WIFI, AmenityTag.PARKING),
        policy = PropertyPolicy(
            checkInTime = LocalTime.of(15, 0),
            checkOutTime = LocalTime.of(11, 0),
            cancellation = CancellationPolicy(CancellationType.FREE_UNTIL, freeUntilDaysBefore = 3),
        ),
    )

    @DisplayName("초기 wishCount 는 0 이다.")
    @Test
    fun shouldStartWithZeroWishCount() {
        val property = newProperty()

        assertThat(property.wishCount).isEqualTo(0)
    }

    @DisplayName("incrementWishCount() 는 카운트를 1 증가시킨다.")
    @Test
    fun shouldIncrementWishCount() {
        val property = newProperty()

        property.incrementWishCount()
        property.incrementWishCount()

        assertThat(property.wishCount).isEqualTo(2)
    }

    @DisplayName("decrementWishCount() 는 카운트를 1 감소시킨다.")
    @Test
    fun shouldDecrementWishCount() {
        val property = newProperty().also {
            it.incrementWishCount()
            it.incrementWishCount()
        }

        property.decrementWishCount()

        assertThat(property.wishCount).isEqualTo(1)
    }

    @DisplayName("decrementWishCount() 는 0 미만으로 떨어지지 않게 CONFLICT 로 거절한다.")
    @Test
    fun shouldReject_whenDecrementBelowZero() {
        val property = newProperty()

        assertThatThrownBy { property.decrementWishCount() }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }
}
