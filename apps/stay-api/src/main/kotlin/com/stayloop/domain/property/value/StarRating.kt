package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * 공식 별 등급 (1~5). NULL 허용 (펜션·게스트하우스는 무등급이 일반).
 * **사용자 평점 `Rating` 과는 의미가 다름** (`docs/design/05 §2.0.2`).
 */
@Embeddable
data class StarRating(
    @Column(name = "star_rating")
    val value: Int,
) {
    init {
        if (value !in MIN..MAX) {
            throw CoreException(ErrorType.BAD_REQUEST, "별 등급은 $MIN~$MAX 사이여야 합니다.")
        }
    }

    companion object {
        private const val MIN = 1
        private const val MAX = 5
    }
}
