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
import org.slf4j.LoggerFactory

/**
 * `BedConfig` VO ↔ JSON 컬럼 변환. autoApply 로 무어노테이션 매핑.
 *
 * `room_types.bed_config` 컬럼은 NOT NULL — null 입력은 즉시 `INTERNAL_ERROR` 로 거절 (Copilot #8 가드).
 * **외부 응답에는 일반화된 메시지만**, Jackson 예외 상세는 로그로 분리. `cause` 로 원인 보존 (Copilot #4, #10 가드).
 *
 * **직렬화·역직렬화 양 측 모두** Jackson 예외를 잡아 통일된 정책으로 래핑하고 (Copilot 3차 가드),
 * 로그에는 raw `dbData` 대신 길이 + 프리뷰만 남겨 부피·민감정보 노출을 줄인다.
 */
@Converter(autoApply = true)
class BedConfigConverter : AttributeConverter<BedConfig, String> {
    override fun convertToDatabaseColumn(attribute: BedConfig?): String {
        if (attribute == null) {
            throw CoreException(ErrorType.INTERNAL_ERROR, "침대 구성 데이터 처리 실패")
        }
        return try {
            OBJECT_MAPPER.writeValueAsString(attribute.beds.mapKeys { it.key.name })
        } catch (e: Exception) {
            log.warn("BedConfig JSON 직렬화 실패. beds={}", attribute.beds.size, e)
            throw CoreException(ErrorType.INTERNAL_ERROR, "침대 구성 데이터 처리 실패", cause = e)
        }
    }

    override fun convertToEntityAttribute(dbData: String?): BedConfig {
        if (dbData.isNullOrBlank()) {
            throw CoreException(ErrorType.INTERNAL_ERROR, "침대 구성 데이터 처리 실패")
        }
        return try {
            val raw: Map<String, Int> = OBJECT_MAPPER.readValue(dbData)
            BedConfig(raw.mapKeys { BedType.valueOf(it.key) })
        } catch (e: CoreException) {
            // BedConfig 자체의 도메인 검증 실패는 그대로 전파 (이미 일반화된 메시지)
            throw e
        } catch (e: Exception) {
            log.warn("BedConfig JSON 역직렬화 실패. length={}, preview='{}'", dbData.length, dbData.take(PREVIEW_LIMIT), e)
            throw CoreException(ErrorType.INTERNAL_ERROR, "침대 구성 데이터 처리 실패", cause = e)
        }
    }

    companion object {
        private const val PREVIEW_LIMIT = 80
        private val log = LoggerFactory.getLogger(BedConfigConverter::class.java)
        private val OBJECT_MAPPER: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    }
}
