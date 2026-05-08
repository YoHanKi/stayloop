package com.stayloop.domain.coupon.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CouponNameTest {

    @DisplayName("정상 생성 시 value 가 그대로 노출되고 toString 으로 표시 가능하다.")
    @Test
    fun shouldExposeValue() {
        val name = CouponName(value = "신규가입 1만원 할인")

        assertThat(name.value).isEqualTo("신규가입 1만원 할인")
        assertThat(name.toString()).isEqualTo("신규가입 1만원 할인")
    }

    @DisplayName("빈 문자열 또는 공백만 있으면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(strings = ["", " ", "    "])
    fun shouldReject_whenBlank(value: String) {
        assertThatThrownBy { CouponName(value = value) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("MAX_NAME_LENGTH(100) 초과 시 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenExceedsMaxLength() {
        val tooLong = "가".repeat(CouponName.MAX_NAME_LENGTH + 1)

        assertThatThrownBy { CouponName(value = tooLong) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
