package com.stayloop.domain.user.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LoginIdTest {
    @DisplayName("영문/숫자 4~20자 형식이면 LoginId 가 정상 생성된다.")
    @ParameterizedTest
    @ValueSource(strings = ["abcd", "user01", "Stayloop2026", "abcdefghij1234567890"])
    fun shouldCreate_whenFormatIsValid(value: String) {
        // act
        val loginId = LoginId(value)

        // assert
        assertThat(loginId.value).isEqualTo(value)
    }

    @DisplayName("영문/숫자가 아니거나 길이가 4~20자를 벗어나면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(strings = ["abc", "abcdefghij1234567890x", "han글", "user 01", "user-01", ""])
    fun shouldReject_whenFormatIsInvalid(value: String) {
        // act / assert
        assertThatThrownBy { LoginId(value) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
