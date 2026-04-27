package com.stayloop.domain.user.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PhoneNumberTest {
    @DisplayName("010-XXXX-XXXX 형식이면 PhoneNumber 가 정상 생성된다.")
    @Test
    fun shouldCreate_whenFormatIsValid() {
        assertThat(PhoneNumber("010-1234-5678").value).isEqualTo("010-1234-5678")
    }

    @DisplayName("010 으로 시작하지 않거나 자릿수가 다르면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(strings = ["011-1234-5678", "010-12345-678", "010-123-5678", "01012345678", "010 1234 5678", ""])
    fun shouldReject_whenFormatIsInvalid(value: String) {
        assertThatThrownBy { PhoneNumber(value) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("masked() 는 가운데 자리만 별표로 가린다.")
    @Test
    fun shouldMaskMiddleDigits() {
        assertThat(PhoneNumber("010-1234-5678").masked()).isEqualTo("010-****-5678")
    }
}
