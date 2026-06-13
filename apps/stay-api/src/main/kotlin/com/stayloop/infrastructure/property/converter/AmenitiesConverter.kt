package com.stayloop.infrastructure.property.converter

import com.fasterxml.jackson.core.type.TypeReference
import com.stayloop.domain.property.value.AmenityTag
import com.stayloop.domain.property.value.Amenities
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * [Amenities] ↔ JSON 배열(`["WIFI","PARKING"]`). autoApply 라 도메인 모델은 본 컨버터를 모른다.
 */
@Converter(autoApply = true)
class AmenitiesConverter : AttributeConverter<Amenities, String> {
    override fun convertToDatabaseColumn(attribute: Amenities?): String =
        PropertyJsonMapper.instance.writeValueAsString(attribute?.tags ?: emptySet<AmenityTag>())

    override fun convertToEntityAttribute(dbData: String?): Amenities {
        if (dbData.isNullOrBlank()) return Amenities.EMPTY
        val tags: Set<AmenityTag> =
            PropertyJsonMapper.instance.readValue(dbData, object : TypeReference<Set<AmenityTag>>() {})
        return Amenities(tags)
    }
}
