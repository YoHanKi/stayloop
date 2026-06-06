package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class BedConfigTest {
    @DisplayName("침대 구성의 총 개수를 합산한다.")
    @Test
    fun shouldSumTotalBeds() {
        val bedConfig = BedConfig(mapOf(BedType.DOUBLE to 1, BedType.SINGLE to 2))

        assertThat(bedConfig.totalBeds()).isEqualTo(3)
    }

    @DisplayName("침대 수가 0 이하인 항목이 있으면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenCountIsNotPositive() {
        assertThatThrownBy { BedConfig(mapOf(BedType.DOUBLE to 0)) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
