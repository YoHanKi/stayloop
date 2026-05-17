package com.stayloop.config

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

/**
 * PR4 D-4 정적/동적 분리 채택안의 cache 전용 RedisTemplate Bean.
 *
 * `modules/redis` 의 `RedisConfig` 와 분리: 기존 `defaultRedisTemplate` 은 `RedisTemplate<String, String>`
 * (값도 String) 으로 일반 Redis access 용. cache 는 Java 객체 (Info / DTO) 를 JSON 직렬화로 저장해야
 * 하므로 value serializer 를 `GenericJackson2JsonRedisSerializer` 로 별도 구성한다.
 *
 * cache 전용 ObjectMapper 빌드 절차:
 * 1. Spring 의 default ObjectMapper (`JacksonConfig` 에서 Kotlin / JSR310 module + customizer 적용된 것) 를 copy
 * 2. `BasicPolymorphicTypeValidator` 로 *허용 base type 화이트리스트* 구성 (`java.lang.Object` 허용)
 * 3. `DefaultTyping.EVERYTHING` 으로 모든 타입에 `@class` type info 활성화 — Kotlin data class 는 final 이라
 *    `NON_FINAL` 로는 적용 안 됨. cache 진입 객체가 *내부 application Info / DTO 한정* 이라 안전.
 *
 * DTO 박제 정책 (verify-code §16-A): cache 에 저장되는 객체는 반드시 application Info 또는 전용 cache DTO.
 * 도메인 모델 (`PropertyModel` / `RoomTypeModel`) 의 직접 직렬화는 금지 — JPA proxy 직렬화 사고 +
 * 모델 변경이 cache 호환성을 깨뜨림.
 *
 * Lettuce connection factory 는 `modules/redis` 의 `defaultRedisConnectionFactory` 를 공유: cache 와 일반
 * Redis access 가 같은 master/replicas 구성을 본다 (단일 인스턴스 가정 — week5.md "Redis Cluster 분산
 * 캐시 — 단일 인스턴스 가정").
 */
@Configuration
class CacheRedisConfig(
    private val objectMapper: ObjectMapper,
) {
    companion object {
        const val CACHE_REDIS_TEMPLATE = "cacheRedisTemplate"
    }

    @Bean(CACHE_REDIS_TEMPLATE)
    fun cacheRedisTemplate(
        connectionFactory: LettuceConnectionFactory,
    ): RedisTemplate<String, Any> {
        val template = RedisTemplate<String, Any>()
        template.connectionFactory = connectionFactory
        template.keySerializer = StringRedisSerializer()
        template.hashKeySerializer = StringRedisSerializer()
        val cacheSerializer = GenericJackson2JsonRedisSerializer(cacheObjectMapper())
        template.valueSerializer = cacheSerializer
        template.hashValueSerializer = cacheSerializer
        template.afterPropertiesSet()
        return template
    }

    private fun cacheObjectMapper(): ObjectMapper {
        val validator = BasicPolymorphicTypeValidator.builder()
            .allowIfBaseType(Any::class.java)
            .build()
        return objectMapper.copy().activateDefaultTyping(
            validator,
            ObjectMapper.DefaultTyping.EVERYTHING,
            JsonTypeInfo.As.PROPERTY,
        )
    }
}
