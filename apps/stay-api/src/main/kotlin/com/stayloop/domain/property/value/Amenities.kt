package com.stayloop.domain.property.value

/**
 * 숙소 편의시설 태그 집합. JSON 컬럼으로 영속화 — 매핑 컨버터는 `infrastructure/property/converter/AmenitiesConverter`.
 */
data class Amenities(
    val tags: Set<AmenityTag>,
) {
    fun has(tag: AmenityTag): Boolean = tags.contains(tag)

    companion object {
        val EMPTY: Amenities = Amenities(emptySet())

        fun of(vararg tags: AmenityTag): Amenities = Amenities(tags.toSet())
    }
}
