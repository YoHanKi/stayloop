package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class AddressTest {
    @DisplayName("도시 코드 또는 주소가 비어 있으면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenBlank() {
        assertThatThrownBy { Address(city = " ", fullAddress = "강남구 ...") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)

        assertThatThrownBy { Address(city = "seoul", fullAddress = "  ") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("좌표는 NULL 허용 — 본 라운드 후순위.")
    @Test
    fun shouldAllowNullCoord() {
        val address = Address(city = "seoul", fullAddress = "강남구 테헤란로 1")

        assertThat(address.coord).isNull()
    }

    @DisplayName("좌표 동반 시 위경도가 보존된다.")
    @Test
    fun shouldHoldCoord() {
        val address = Address(
            city = "seoul",
            fullAddress = "강남구 테헤란로 1",
            coord = Geo(latitude = 37.5, longitude = 127.0),
        )

        assertThat(address.coord?.latitude).isEqualTo(37.5)
    }
}
