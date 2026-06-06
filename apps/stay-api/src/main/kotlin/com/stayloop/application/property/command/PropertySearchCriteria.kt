package com.stayloop.application.property.command

import com.stayloop.domain.reservation.value.StayPeriod
import java.time.LocalDate

/**
 * 숙소 검색 조건. 페이징을 기본으로 받아 필터 없이 수천 건을 반환하지 않는다.
 * 기본 정렬은 [PropertySortKey.RECOMMENDED] 이고 그 외 정렬은 P1(4주차)로, 지금은 명시적으로 거절한다.
 */
data class PropertySearchCriteria(
    val city: String,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val guestCount: Int,
    val sortKey: PropertySortKey = PropertySortKey.RECOMMENDED,
    val page: Int = 0,
    val size: Int = 20,
) {
    fun period(): StayPeriod = StayPeriod(checkIn, checkOut)
}

enum class PropertySortKey {
    RECOMMENDED,
    PRICE_ASC,
    RATING_DESC,
    WISHES_DESC,
}
