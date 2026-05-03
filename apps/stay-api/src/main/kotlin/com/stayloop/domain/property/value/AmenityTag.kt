package com.stayloop.domain.property.value

/**
 * 숙소 편의시설 태그. (`docs/design/03-class-diagram.md §1`)
 * 단위 시설(레스토랑/회의실 등)이 아닌 **태그** — 단위 시설은 별도 테이블이 들어올 자리 (`05 §2.0`).
 */
enum class AmenityTag {
    WIFI,
    PARKING,
    POOL,
    BREAKFAST,
    GYM,
    SPA,
    PET_FRIENDLY,
    SMOKING_ROOM,
    BUSINESS_CENTER,
    LAUNDRY,
    AIRPORT_SHUTTLE,
    KITCHEN,
}
