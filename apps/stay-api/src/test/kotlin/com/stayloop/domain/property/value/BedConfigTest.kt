package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class BedConfigTest {
    @DisplayName("빈 침대 구성은 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenEmpty() {
        assertThatThrownBy { BedConfig(emptyMap()) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("침대 종류별 수량이 0 이하이면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenAnyCountIsZero() {
        assertThatThrownBy { BedConfig(mapOf(BedType.DOUBLE to 0)) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("totalBeds() 는 모든 침대 수량의 합이다.")
    @Test
    fun shouldSumTotalBeds() {
        val config = BedConfig.of(BedType.DOUBLE to 1, BedType.SINGLE to 2)

        assertThat(config.totalBeds()).isEqualTo(3)
    }
}
