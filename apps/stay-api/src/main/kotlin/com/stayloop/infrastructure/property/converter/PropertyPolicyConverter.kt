package com.stayloop.infrastructure.property.converter

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.stayloop.domain.property.value.PropertyPolicy
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import org.slf4j.LoggerFactory

/**
 * `PropertyPolicy` VO ↔ JSON 컬럼 변환.
 * Jackson 의존이 도메인에 누출되지 않도록 infrastructure 레이어에 위치 (`docs/design/04-erd.md §2.5`).
 *
 * `properties.policy` 컬럼은 NOT NULL — null 입력은 즉시 `INTERNAL_ERROR` 로 거절.
 * **외부 응답에는 일반화된 메시지만**, Jackson 예외 상세는 로그로 분리. `cause` 로 원인 보존 (Copilot #3, #9 가드).
 */
@Converter(autoApply = true)
class PropertyPolicyConverter : AttributeConverter<PropertyPolicy, String> {
    override fun convertToDatabaseColumn(attribute: PropertyPolicy?): String {
        if (attribute == null) {
            throw CoreException(ErrorType.INTERNAL_ERROR, "정책 데이터 처리 실패")
        }
        return OBJECT_MAPPER.writeValueAsString(attribute)
    }

    override fun convertToEntityAttribute(dbData: String?): PropertyPolicy {
        if (dbData.isNullOrBlank()) {
            throw CoreException(ErrorType.INTERNAL_ERROR, "정책 데이터 처리 실패")
        }
        return try {
            OBJECT_MAPPER.readValue(dbData)
        } catch (e: Exception) {
            log.warn("PropertyPolicy JSON 역직렬화 실패. dbData='{}'", dbData, e)
            throw CoreException(ErrorType.INTERNAL_ERROR, "정책 데이터 처리 실패", cause = e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PropertyPolicyConverter::class.java)
        private val OBJECT_MAPPER: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    }
}
