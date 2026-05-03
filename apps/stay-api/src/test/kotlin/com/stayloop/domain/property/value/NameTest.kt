package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NameTest {
    @DisplayName("공백이면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(strings = ["", " ", "   "])
    fun shouldReject_whenBlank(value: String) {
        assertThatThrownBy { Name(value) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("100자를 초과하면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenTooLong() {
        val tooLong = "가".repeat(101)

        assertThatThrownBy { Name(tooLong) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("정상 이름은 toString 으로 그대로 노출된다.")
    @Test
    fun shouldExposeValueAsString() {
        assertThat(Name("강남 라마다 호텔").toString()).isEqualTo("강남 라마다 호텔")
    }
}
