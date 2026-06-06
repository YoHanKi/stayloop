package com.stayloop.domain.property.value

/**
 * 편의시설 태그 집합. DB 에는 JSON 배열(`["WIFI","PARKING"]`)로 직렬화한다
 * (`infrastructure/property/converter/AmenitiesConverter`, autoApply). 도메인은
 * Jackson 을 모른 채 남기기 위해 어노테이션을 두지 않는다(03 §1 결정).
 */
data class Amenities(
    val tags: Set<AmenityTag> = emptySet(),
) {
    fun contains(tag: AmenityTag): Boolean = tags.contains(tag)

    companion object {
        val EMPTY = Amenities(emptySet())
    }
}
