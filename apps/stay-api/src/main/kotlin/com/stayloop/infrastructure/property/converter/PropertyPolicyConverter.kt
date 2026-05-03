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
 */
@Converter(autoApply = true)
class PropertyPolicyConverter : AttributeConverter<PropertyPolicy, String> {
    override fun convertToDatabaseColumn(attribute: PropertyPolicy?): String? =
        attribute?.let { OBJECT_MAPPER.writeValueAsString(it) }

    override fun convertToEntityAttribute(dbData: String?): PropertyPolicy? =
        dbData?.let {
            try {
                OBJECT_MAPPER.readValue<PropertyPolicy>(it)
            } catch (e: Exception) {
                throw CoreException(ErrorType.INTERNAL_ERROR, "PropertyPolicy JSON 역직렬화 실패: ${e.message}")
            }
        }

    companion object {
        private val OBJECT_MAPPER: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    }
}
