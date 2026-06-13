package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * 객실 타입의 수용 인원. [baseGuests] 는 기준 인원, [maxGuests] 는 최대 인원이다.
 */
@Embeddable
data class GuestCount(
    @Column(name = "base_guests", nullable = false)
    val baseGuests: Int,
    @Column(name = "max_guests", nullable = false)
    val maxGuests: Int,
) {
    init {
        if (baseGuests < 1) {
            throw CoreException(ErrorType.BAD_REQUEST, "기준 인원은 1명 이상이어야 합니다.")
        }
        if (maxGuests < baseGuests) {
            throw CoreException(ErrorType.BAD_REQUEST, "최대 인원은 기준 인원보다 적을 수 없습니다.")
        }
    }

    /** 요청 인원이 1명 이상이고 최대 인원 이하인지 확인한다. */
    fun canAccommodate(requested: Int): Boolean = requested in 1..maxGuests
}
