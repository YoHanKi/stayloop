package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class RatingTest {
    @DisplayName("0.0~5.0 범위면 정상 생성된다.")
    @Test
    fun shouldCreate_whenWithinRange() {
        assertThat(Rating.of(4.5).value).isEqualByComparingTo(BigDecimal("4.50"))
    }

    @DisplayName("리뷰가 없는 신규 숙소의 ZERO 평점은 0.00 이다.")
    @Test
    fun shouldExposeZero() {
        assertThat(Rating.ZERO.value).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @DisplayName("5.0 을 초과하거나 음수면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenOutOfRange() {
        assertThatThrownBy { Rating(BigDecimal("5.01")) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
        assertThatThrownBy { Rating(BigDecimal("-0.01")) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
