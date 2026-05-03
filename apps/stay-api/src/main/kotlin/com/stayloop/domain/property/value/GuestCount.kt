package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * 객실 타입의 기준/최대 인원. (`docs/design/03-class-diagram.md §1`, `04-erd.md §1`)
 * `base` 초과 시의 추가 요금 정책은 본 라운드 스코프 외 — 단순 `max` 검증만.
 */
@Embeddable
data class GuestCount(
    @Column(name = "base_guests", nullable = false)
    val base: Int,
    @Column(name = "max_guests", nullable = false)
    val max: Int,
) {
    init {
        if (base <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "기준 인원은 1 이상이어야 합니다.")
        }
        if (max < base) {
            throw CoreException(ErrorType.BAD_REQUEST, "최대 인원은 기준 인원 이상이어야 합니다.")
        }
    }
}
