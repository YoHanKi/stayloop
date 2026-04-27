package com.stayloop.domain.user.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import com.stayloop.support.test.FakePasswordEncoder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate

class PasswordTest {
    private val encoder = FakePasswordEncoder()
    private val birthDate = BirthDate(LocalDate.of(2000, 1, 1))

    @DisplayName("8~16자의 영문/숫자/특수문자로 구성되면 Password 가 인코딩된 형태로 생성된다.")
    @ParameterizedTest
    @ValueSource(strings = ["Abcd1234", "Abcd1234!@#$", "P@ssw0rd!Secur3"])
    fun shouldCreate_whenPolicySatisfied(raw: String) {
        // act
        val password = Password.ofRaw(raw, birthDate, encoder)

        // assert
        assertThat(password.encoded).isEqualTo("${FakePasswordEncoder.PREFIX}$raw")
    }

    @DisplayName("길이가 8자 미만이거나 16자 초과이면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(strings = ["Abc1!", "Abcd1234Abcd12345"])
    fun shouldReject_whenLengthOutOfRange(raw: String) {
        assertThatThrownBy { Password.ofRaw(raw, birthDate, encoder) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("허용되지 않은 문자(공백, 한글 등)가 포함되면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(strings = ["Abcd 1234", "Abcd1234한글", "Ab\t12345"])
    fun shouldReject_whenContainsForbiddenChars(raw: String) {
        assertThatThrownBy { Password.ofRaw(raw, birthDate, encoder) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("비밀번호 내에 생년월일(yyyyMMdd) 이 포함되면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenContainsBirthDate() {
        assertThatThrownBy { Password.ofRaw("20000101pass", birthDate, encoder) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("matches() 는 인코더 동작 결과를 그대로 위임한다.")
    @Test
    fun shouldDelegateMatches() {
        // arrange
        val password = Password.ofRaw("Abcd1234!", birthDate, encoder)

        // assert
        assertThat(password.matches("Abcd1234!", encoder)).isTrue()
        assertThat(password.matches("Wrong1234!", encoder)).isFalse()
    }

    @DisplayName("ofEncoded() 는 정책 검증 없이 저장된 해시를 그대로 감싼다.")
    @Test
    fun shouldWrapEncoded_withoutValidation() {
        val password = Password.ofEncoded("any-hash-value")

        assertThat(password.encoded).isEqualTo("any-hash-value")
    }
}
