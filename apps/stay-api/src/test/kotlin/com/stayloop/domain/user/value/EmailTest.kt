package com.stayloop.domain.user.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class EmailTest {
    @DisplayName("표준 이메일 형식이면 Email 이 정상 생성된다.")
    @ParameterizedTest
    @ValueSource(strings = ["a@b.co", "user.name+tag@example.com", "USER_01@stayloop.io"])
    fun shouldCreate_whenFormatIsValid(value: String) {
        assertThat(Email(value).value).isEqualTo(value)
    }

    @DisplayName("@ 누락 또는 도메인 형식 위반 시 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(strings = ["", "no-at-sign", "no@domain", "@domain.com", "user@.com", "user@domain"])
    fun shouldReject_whenFormatIsInvalid(value: String) {
        assertThatThrownBy { Email(value) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
