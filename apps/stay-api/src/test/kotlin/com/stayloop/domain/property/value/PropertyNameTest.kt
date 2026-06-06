package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PropertyNameTest {
    @DisplayName("1~100 자면 정상 생성된다.")
    @Test
    fun shouldCreate_whenWithinLength() {
        assertThat(PropertyName("스테이루프 호텔").value).isEqualTo("스테이루프 호텔")
    }

    @DisplayName("공백이거나 100 자를 초과하면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenBlankOrTooLong() {
        assertThatThrownBy { PropertyName(" ") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
        assertThatThrownBy { PropertyName("가".repeat(101)) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
