package com.stayloop.infrastructure.property.converter

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature

/**
 * JSON 컬럼 컨버터들이 공유하는 ObjectMapper.
 *
 * JPA AttributeConverter 는 Hibernate 가 직접 인스턴스화하므로 Spring 의 ObjectMapper 빈을
 * 주입받지 못한다. [findAndRegisterModules] 로 런타임 classpath 의 KotlinModule / JavaTimeModule
 * 을 ServiceLoader 등록해 Kotlin data class 와 `LocalTime` 직렬화를 지원한다.
 */
internal object PropertyJsonMapper {
    val instance: ObjectMapper =
        ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}
