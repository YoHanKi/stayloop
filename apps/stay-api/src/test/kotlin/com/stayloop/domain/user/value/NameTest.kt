package com.stayloop.domain.user.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NameTest {
    @DisplayName("공백이거나 50자를 초과하면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(strings = ["", " ", "   "])
    fun shouldReject_whenBlank(value: String) {
        assertThatThrownBy { Name(value) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("50자를 초과하면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenLongerThan50() {
        val tooLong = "가".repeat(51)

        assertThatThrownBy { Name(tooLong) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("masked() 는 마지막 글자를 별표로 가린다. 한 글자 이름은 전체가 가려진다.")
    @Test
    fun shouldMaskLastCharacter() {
        assertThat(Name("홍길동").masked()).isEqualTo("홍길*")
        assertThat(Name("Alen").masked()).isEqualTo("Ale*")
        assertThat(Name("김").masked()).isEqualTo("*")
    }
}
