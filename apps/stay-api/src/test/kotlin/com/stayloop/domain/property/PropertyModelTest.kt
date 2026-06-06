package com.stayloop.domain.property

import com.stayloop.domain.property.value.Address
import com.stayloop.domain.property.value.PropertyCategory
import com.stayloop.domain.property.value.PropertyName
import com.stayloop.domain.property.value.PropertyPolicy
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PropertyModelTest {
    private fun newProperty(): PropertyModel =
        PropertyModel.create(
            name = PropertyName("스테이루프 호텔"),
            category = PropertyCategory.HOTEL,
            address = Address(city = "seoul", roadAddress = "서울특별시 중구 세종대로 110"),
            policy = PropertyPolicy.standard(),
        )

    @DisplayName("대표 이미지로 추가하면 mainImageUrl 캐시가 갱신되고 해당 이미지가 대표가 된다.")
    @Test
    fun shouldSetMainImage_whenAddedAsMain() {
        val property = newProperty()

        property.addImage("https://img/1.jpg", isMain = true)

        assertThat(property.mainImageUrl).isEqualTo("https://img/1.jpg")
        assertThat(property.images.single { it.isMain }.imageUrl).isEqualTo("https://img/1.jpg")
    }

    @DisplayName("대표 이미지를 새로 추가하면 기존 대표는 해제되어 대표는 항상 0~1 개다.")
    @Test
    fun shouldKeepSingleMain_whenAnotherMainAdded() {
        val property = newProperty()
        property.addImage("https://img/1.jpg", isMain = true)

        property.addImage("https://img/2.jpg", isMain = true)

        assertThat(property.images.count { it.isMain }).isEqualTo(1)
        assertThat(property.mainImageUrl).isEqualTo("https://img/2.jpg")
    }

    @DisplayName("replaceMainImage 는 갤러리에 있는 이미지를 단일 대표로 만든다.")
    @Test
    fun shouldReplaceMainImage_whenImageExists() {
        val property = newProperty()
        property.addImage("https://img/1.jpg", isMain = true)
        property.addImage("https://img/2.jpg")

        property.replaceMainImage("https://img/2.jpg")

        assertThat(property.mainImageUrl).isEqualTo("https://img/2.jpg")
        assertThat(property.images.single { it.isMain }.imageUrl).isEqualTo("https://img/2.jpg")
    }

    @DisplayName("replaceMainImage 대상 이미지가 갤러리에 없으면 NOT_FOUND 로 거절된다.")
    @Test
    fun shouldReject_whenMainImageNotInGallery() {
        val property = newProperty()

        assertThatThrownBy { property.replaceMainImage("https://img/none.jpg") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND)
    }

    @DisplayName("찜 수는 증가하고, 0 에서 감소하면 음수로 새지 않고 0 을 유지한다.")
    @Test
    fun shouldGuardWishCount() {
        val property = newProperty()

        property.incrementWishCount()
        property.incrementWishCount()
        property.decrementWishCount()
        assertThat(property.wishCount).isEqualTo(1)

        property.decrementWishCount()
        property.decrementWishCount()
        assertThat(property.wishCount).isEqualTo(0)
    }
}
