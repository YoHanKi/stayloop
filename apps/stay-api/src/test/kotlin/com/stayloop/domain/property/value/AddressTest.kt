package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class AddressTest {
    @DisplayName("도시와 주소가 모두 있으면 정상 생성된다.")
    @Test
    fun shouldCreate_whenBothPresent() {
        val address = Address(city = "seoul", roadAddress = "서울특별시 중구 세종대로 110")

        assertThat(address.city).isEqualTo("seoul")
    }

    @DisplayName("도시가 비어 있으면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenCityBlank() {
        assertThatThrownBy { Address(city = " ", roadAddress = "주소") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("주소가 비어 있으면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenRoadAddressBlank() {
        assertThatThrownBy { Address(city = "seoul", roadAddress = " ") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
