package com.stayloop.infrastructure.property.converter

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.stayloop.domain.property.value.BedConfig
import com.stayloop.domain.property.value.BedType
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `BedConfig` VO ↔ JSON 컬럼 변환. autoApply 로 무어노테이션 매핑.
 */
@Converter(autoApply = true)
class BedConfigConverter : AttributeConverter<BedConfig, String> {
    override fun convertToDatabaseColumn(attribute: BedConfig?): String =
        OBJECT_MAPPER.writeValueAsString(
            (attribute?.beds ?: emptyMap()).mapKeys { it.key.name },
        )

    override fun convertToEntityAttribute(dbData: String?): BedConfig {
        if (dbData.isNullOrBlank()) {
            throw CoreException(ErrorType.INTERNAL_ERROR, "BedConfig JSON 이 비어 있습니다.")
        }
        return try {
            val raw: Map<String, Int> = OBJECT_MAPPER.readValue(dbData)
            BedConfig(raw.mapKeys { BedType.valueOf(it.key) })
        } catch (e: Exception) {
            throw CoreException(ErrorType.INTERNAL_ERROR, "BedConfig JSON 역직렬화 실패: ${e.message}")
        }
    }

    companion object {
        private val OBJECT_MAPPER: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    }
}
