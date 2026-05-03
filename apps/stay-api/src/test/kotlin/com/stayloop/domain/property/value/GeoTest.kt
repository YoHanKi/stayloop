package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class GeoTest {
    @DisplayName("위도가 -90 ~ 90 범위를 벗어나면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenLatitudeOutOfRange() {
        assertThatThrownBy { Geo(latitude = 91.0, longitude = 127.0) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)

        assertThatThrownBy { Geo(latitude = -91.0, longitude = 127.0) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("경도가 -180 ~ 180 범위를 벗어나면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenLongitudeOutOfRange() {
        assertThatThrownBy { Geo(latitude = 37.5, longitude = 181.0) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("정상 범위의 위경도는 그대로 보유된다.")
    @Test
    fun shouldHoldValidCoord() {
        val seoul = Geo(latitude = 37.5665, longitude = 126.9780)

        assertThat(seoul.latitude).isEqualTo(37.5665)
        assertThat(seoul.longitude).isEqualTo(126.9780)
    }
}
