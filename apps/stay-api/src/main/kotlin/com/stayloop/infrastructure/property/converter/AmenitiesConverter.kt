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
import org.slf4j.LoggerFactory

/**
 * `Amenities` VO ↔ JSON 배열 컬럼 변환. (`docs/design/04-erd.md §2.5`)
 *
 * `properties.amenities` 컬럼은 NOT NULL 이고 도메인 타입도 non-null 이므로
 * **null 입력 즉시 INTERNAL_ERROR 거절** — 다른 컨버터(PropertyPolicy/BedConfig)와 정책 일관 (Copilot #2 가드).
 *
 * **외부 응답에는 일반화된 메시지만 노출**, Jackson 예외 상세는 로그로 분리. `cause` 로 원인 보존.
 */
@Converter(autoApply = true)
class AmenitiesConverter : AttributeConverter<Amenities, String> {
    override fun convertToDatabaseColumn(attribute: Amenities?): String {
        if (attribute == null) {
            throw CoreException(ErrorType.INTERNAL_ERROR, "편의시설 데이터 처리 실패")
        }
        return OBJECT_MAPPER.writeValueAsString(attribute.tags.map { it.name })
    }

    override fun convertToEntityAttribute(dbData: String?): Amenities {
        if (dbData.isNullOrBlank()) {
            throw CoreException(ErrorType.INTERNAL_ERROR, "편의시설 데이터 처리 실패")
        }
        return try {
            val tags: Set<AmenityTag> = OBJECT_MAPPER.readValue<Set<String>>(dbData)
                .map { AmenityTag.valueOf(it) }
                .toSet()
            Amenities(tags)
        } catch (e: Exception) {
            log.warn("Amenities JSON 역직렬화 실패. dbData='{}'", dbData, e)
            throw CoreException(ErrorType.INTERNAL_ERROR, "편의시설 데이터 처리 실패", cause = e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AmenitiesConverter::class.java)
        private val OBJECT_MAPPER: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    }
}
