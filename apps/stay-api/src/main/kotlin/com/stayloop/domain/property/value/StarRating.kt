package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * 숙소 성급(1~5성). 등급제 숙소(호텔·리조트)만 가지며, 무등급 숙소는 모델에서 null 로 둔다.
 */
@Embeddable
data class StarRating(
    @Column(name = "star_rating")
    val value: Int,
) {
    init {
        if (value !in MIN..MAX) {
            throw CoreException(ErrorType.BAD_REQUEST, "성급은 $MIN~$MAX 사이여야 합니다.")
        }
    }

    companion object {
        private const val MIN = 1
        private const val MAX = 5
    }
}
