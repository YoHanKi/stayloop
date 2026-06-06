package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 리뷰 평균 평점(0.0~5.0). 리뷰가 없는 신규 숙소는 [ZERO].
 */
@Embeddable
data class Rating(
    @Column(name = "rating", nullable = false, precision = 3, scale = 2)
    val value: BigDecimal,
) {
    init {
        if (value < MIN || value > MAX) {
            throw CoreException(ErrorType.BAD_REQUEST, "평점은 0.0~5.0 사이여야 합니다.")
        }
    }

    companion object {
        private val MIN = BigDecimal("0.00")
        private val MAX = BigDecimal("5.00")

        val ZERO = Rating(BigDecimal.ZERO.setScale(2))

        fun of(value: Double): Rating =
            Rating(BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP))
    }
}
