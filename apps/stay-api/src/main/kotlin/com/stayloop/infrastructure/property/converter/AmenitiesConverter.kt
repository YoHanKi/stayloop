package com.stayloop.infrastructure.property.converter

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.stayloop.domain.property.value.Amenities
import com.stayloop.domain.property.value.AmenityTag
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `Amenities` VO ↔ JSON 배열 컬럼 변환. (`docs/design/04-erd.md §2.5`)
 */
@Converter
class AmenitiesConverter : AttributeConverter<Amenities, String> {
    override fun convertToDatabaseColumn(attribute: Amenities?): String =
        OBJECT_MAPPER.writeValueAsString(
            (attribute ?: Amenities.EMPTY).tags.map { it.name },
        )

    override fun convertToEntityAttribute(dbData: String?): Amenities {
        if (dbData.isNullOrBlank()) return Amenities.EMPTY
        return try {
            val tags: Set<AmenityTag> = OBJECT_MAPPER.readValue<Set<String>>(dbData)
                .map { AmenityTag.valueOf(it) }
                .toSet()
            Amenities(tags)
        } catch (e: Exception) {
            throw CoreException(ErrorType.INTERNAL_ERROR, "Amenities JSON 역직렬화 실패: ${e.message}")
        }
    }

    companion object {
        private val OBJECT_MAPPER: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    }
}
