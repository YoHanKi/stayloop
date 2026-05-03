package com.stayloop.domain.property.value

/**
 * 숙소 카테고리. (`docs/design/04-erd.md §1`)
 * 검색 필터 후순위로 활용.
 */
enum class PropertyCategory {
    HOTEL,
    MOTEL,
    PENSION,
    RESORT,
    GUESTHOUSE,
}
