package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * 사용자 평점 (0.00 ~ 5.00). 본 라운드는 placeholder — 리뷰 합류 시 비동기 재계산.
 * **공식 별 등급 `StarRating` 과는 의미가 다름** (`docs/design/03 §1`, `05 §2.7`).
 */
@Embeddable
data class Rating(
    // Hibernate 6.x 는 DOUBLE 컬럼에 `scale` 속성을 거절 (`scale has no meaning for SQL floating point types`).
    // 평점은 0.00 ~ 5.00 의 소수 둘째 자리 정밀도가 본질이므로 DB 컬럼은 DECIMAL(3,2) 로 고정 (Geo.kt 와 동일 패턴).
    @Column(name = "rating", columnDefinition = "DECIMAL(3,2)", nullable = false)
    val value: Double,
) {
    init {
        if (value !in MIN..MAX) {
            throw CoreException(ErrorType.BAD_REQUEST, "평점은 $MIN ~ $MAX 사이여야 합니다.")
        }
    }

    companion object {
        private const val MIN = 0.0
        private const val MAX = 5.0
        val ZERO: Rating = Rating(0.0)
    }
}
