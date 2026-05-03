package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class StarRatingTest {
    @DisplayName("1 ~ 5 범위를 벗어나면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(ints = [0, 6, -1, 100])
    fun shouldReject_whenOutOfRange(value: Int) {
        assertThatThrownBy { StarRating(value) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("1 ~ 5 범위는 정상 보유된다.")
    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 4, 5])
    fun shouldAccept_validRange(value: Int) {
        assertThat(StarRating(value).value).isEqualTo(value)
    }
}

class RatingTest {
    @DisplayName("0.0 ~ 5.0 범위를 벗어나면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenOutOfRange() {
        assertThatThrownBy { Rating(-0.1) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)

        assertThatThrownBy { Rating(5.1) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("ZERO 는 0.0 평점을 의미한다.")
    @Test
    fun shouldExposeZero() {
        assertThat(Rating.ZERO.value).isEqualTo(0.0)
    }
}
