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
 *
 * `room_types.bed_config` 컬럼은 NOT NULL, 도메인 타입도 non-null 이므로
 * **null 입력은 즉시 `INTERNAL_ERROR` 로 거절** — 빈 맵으로 조용히 변환되어
 * 역직렬화 시점에서야 폭발하는 동작을 막는다 (Copilot #8 가드).
 */
@Converter(autoApply = true)
class BedConfigConverter : AttributeConverter<BedConfig, String> {
    override fun convertToDatabaseColumn(attribute: BedConfig?): String {
        if (attribute == null) {
            throw CoreException(ErrorType.INTERNAL_ERROR, "BedConfig 는 null 일 수 없습니다 (NOT NULL 컬럼).")
        }
        return OBJECT_MAPPER.writeValueAsString(attribute.beds.mapKeys { it.key.name })
    }

    override fun convertToEntityAttribute(dbData: String?): BedConfig {
        if (dbData.isNullOrBlank()) {
            throw CoreException(ErrorType.INTERNAL_ERROR, "BedConfig JSON 이 비어 있습니다 (NOT NULL 컬럼).")
        }
        return try {
            val raw: Map<String, Int> = OBJECT_MAPPER.readValue(dbData)
            BedConfig(raw.mapKeys { BedType.valueOf(it.key) })
        } catch (e: CoreException) {
            // BedConfig 자체의 도메인 검증 실패는 그대로 전파
            throw e
        } catch (e: Exception) {
            throw CoreException(ErrorType.INTERNAL_ERROR, "BedConfig JSON 역직렬화 실패: ${e.message}")
        }
    }

    companion object {
        private val OBJECT_MAPPER: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    }
}
