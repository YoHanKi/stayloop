package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class StarRatingTest {
    @DisplayName("1~5 성이면 정상 생성된다.")
    @ParameterizedTest
    @ValueSource(ints = [1, 3, 5])
    fun shouldCreate_whenWithinRange(value: Int) {
        assertThat(StarRating(value).value).isEqualTo(value)
    }

    @DisplayName("1 미만이거나 5 초과면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(ints = [0, 6, -1])
    fun shouldReject_whenOutOfRange(value: Int) {
        assertThatThrownBy { StarRating(value) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
