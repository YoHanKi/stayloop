package com.stayloop.infrastructure.property.converter

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.stayloop.domain.property.value.PropertyPolicy
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `PropertyPolicy` VO ↔ JSON 컬럼 변환.
 * Jackson 의존이 도메인에 누출되지 않도록 infrastructure 레이어에 위치 (`docs/design/04-erd.md §2.5`).
 *
 * `properties.policy` 컬럼은 NOT NULL, 도메인 타입도 non-null 이므로
 * **null 입력은 즉시 `INTERNAL_ERROR` 로 거절** — 데이터 정합성 사고를 침묵시키지 않음 (Copilot #1 가드).
 */
@Converter(autoApply = true)
class PropertyPolicyConverter : AttributeConverter<PropertyPolicy, String> {
    override fun convertToDatabaseColumn(attribute: PropertyPolicy?): String {
        if (attribute == null) {
            throw CoreException(ErrorType.INTERNAL_ERROR, "PropertyPolicy 는 null 일 수 없습니다 (NOT NULL 컬럼).")
        }
        return OBJECT_MAPPER.writeValueAsString(attribute)
    }

    override fun convertToEntityAttribute(dbData: String?): PropertyPolicy {
        if (dbData.isNullOrBlank()) {
            throw CoreException(ErrorType.INTERNAL_ERROR, "PropertyPolicy JSON 이 비어 있습니다 (NOT NULL 컬럼).")
        }
        return try {
            OBJECT_MAPPER.readValue(dbData)
        } catch (e: Exception) {
            throw CoreException(ErrorType.INTERNAL_ERROR, "PropertyPolicy JSON 역직렬화 실패: ${e.message}")
        }
    }

    companion object {
        private val OBJECT_MAPPER: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    }
}
