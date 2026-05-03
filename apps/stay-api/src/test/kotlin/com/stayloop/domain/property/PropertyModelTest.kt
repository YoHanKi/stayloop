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

    @DisplayName("addImage(isMain = true) 는 기존 main 의 플래그를 해제하고 새 이미지를 main 으로 등록한다.")
    @Test
    fun shouldReplaceMain_whenAddingMainImage() {
        val property = newProperty()

        val first = property.addImage("https://cdn/p/1.jpg", isMain = true, displayOrder = 0)
        val second = property.addImage("https://cdn/p/2.jpg", isMain = true, displayOrder = 1)

        assertThat(first.isMain).isFalse()
        assertThat(second.isMain).isTrue()
        assertThat(property.mainImageUrl).isEqualTo("https://cdn/p/2.jpg")
    }

    @DisplayName("replaceMainImage() 는 mainImageUrl 캐시와 갤러리의 is_main 플래그를 함께 갱신한다.")
    @Test
    fun shouldReplaceMainImage_andSyncFlag() {
        val property = newProperty()
        property.addImage("https://cdn/p/1.jpg", isMain = true)
        property.addImage("https://cdn/p/2.jpg", isMain = false)

        property.replaceMainImage("https://cdn/p/2.jpg")

        assertThat(property.mainImageUrl).isEqualTo("https://cdn/p/2.jpg")
        assertThat(property.images).extracting("imageUrl", "isMain").containsExactly(
            org.assertj.core.groups.Tuple.tuple("https://cdn/p/1.jpg", false),
            org.assertj.core.groups.Tuple.tuple("https://cdn/p/2.jpg", true),
        )
    }

    @DisplayName("replaceMainImage() 빈 URL 은 BAD_REQUEST 로 거절한다.")
    @Test
    fun shouldReject_replaceMainImageWithBlank() {
        val property = newProperty()

        assertThatThrownBy { property.replaceMainImage("  ") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("replaceMainImage() 갤러리에 없는 URL 은 BAD_REQUEST 로 거절하고 상태가 변하지 않는다.")
    @Test
    fun shouldReject_replaceMainImageWithUnknownUrl() {
        val property = newProperty()
        property.addImage("https://cdn/p/1.jpg", isMain = true)

        assertThatThrownBy { property.replaceMainImage("https://cdn/unknown.jpg") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)

        assertThat(property.mainImageUrl).isEqualTo("https://cdn/p/1.jpg")
        assertThat(property.images.single().isMain).isTrue()
    }

    @DisplayName("addImage(isMain=true, invalid url) 호출 시 기존 main 플래그가 해제되지 않고 aggregate 상태가 보존된다.")
    @Test
    fun shouldPreserveState_whenAddImageFailsValidation() {
        val property = newProperty()
        property.addImage("https://cdn/p/1.jpg", isMain = true)

        // 빈 URL 은 PropertyImageModel 생성 단계에서 거절
        assertThatThrownBy { property.addImage(imageUrl = "  ", isMain = true) }
            .isInstanceOf(CoreException::class.java)

        // 실패 후에도 기존 main 플래그가 유지되어야 함
        assertThat(property.images).hasSize(1)
        assertThat(property.images.single().isMain).isTrue()
        assertThat(property.mainImageUrl).isEqualTo("https://cdn/p/1.jpg")
    }
}
