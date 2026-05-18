package com.stayloop.infrastructure.cache

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

/**
 * week5 PR4 A-6 — `RedisCacheStore` 의 *Redis 다운 fallback* 단위 검증.
 *
 * **검증 의도** — D-4 채택안의 *graceful degradation (Q5)*:
 * - `get` / `getOrPut` / `put` / `evict` / `evictPattern` 가 `DataAccessException` 을 *모두 catch* 해
 *   서비스가 죽지 않는지
 * - `getOrPut` 의 fallback 시 loader 결과를 그대로 반환하는지 (cache 가 *없는 것처럼* 작동)
 * - cache put 실패는 silent (catch only, throw 안 함)
 *
 * **Testcontainers 사용 X — `RedisTemplate` mockk 로 직접 다운 시나리오 시뮬레이션**. Redis 컨테이너를 stop
 * 하는 방식은 Lettuce reconnect timeout 으로 *테스트 시간 크게 증가*. mock 으로 *동작 본질* 만 검증.
 */
class RedisCacheStoreFallbackTest {
    private val redisTemplate: RedisTemplate<String, Any> = mockk(relaxed = true)
    private val valueOps: ValueOperations<String, Any> = mockk(relaxed = true)
    private val sut = RedisCacheStore(redisTemplate)

    @DisplayName("Redis 다운 시 get 은 null 을 반환한다 (silent fallback).")
    @Test
    fun getReturnsNullOnRedisDown() {
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.get(any()) } throws RedisConnectionFailureException("redis down")

        val result = sut.get("any:key", String::class.java)

        assertThat(result).isNull()
    }

    @DisplayName("Redis 다운 시 getOrPut 은 loader 결과를 그대로 반환하고 put 시도는 silent fail 한다.")
    @Test
    fun getOrPutFallsBackToLoaderResultOnRedisDown() {
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.get(any()) } throws RedisConnectionFailureException("redis down")
        every { valueOps.set(any(), any(), any<Duration>()) } throws RedisConnectionFailureException("redis down")

        val result = sut.getOrPut("any:key", Duration.ofMinutes(1), String::class.java) {
            "loaded-from-db"
        }

        assertThat(result).isEqualTo("loaded-from-db")
        // put 시도는 한 번 발생 (fallback 결과를 cache 에 넣으려는 시도) 하지만 silent fail
        verify(atLeast = 1) { valueOps.set(any(), any(), any<Duration>()) }
    }

    @DisplayName("Redis 다운 시 put 은 silent fail (예외 미전파).")
    @Test
    fun putSilentlyFailsOnRedisDown() {
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.set(any(), any(), any<Duration>()) } throws QueryTimeoutException("timeout")

        // 예외가 호출자에게 전파되면 안 됨
        sut.put("any:key", "value", Duration.ofMinutes(1))
    }

    @DisplayName("Redis 다운 시 evict 는 silent fail.")
    @Test
    fun evictSilentlyFailsOnRedisDown() {
        every { redisTemplate.delete(any<String>()) } throws RedisConnectionFailureException("redis down")

        sut.evict("any:key")
    }

    @DisplayName("Redis 다운 시 evictPattern (KEYS) 은 silent fail.")
    @Test
    fun evictPatternSilentlyFailsOnRedisDown() {
        every { redisTemplate.keys(any<String>()) } throws RedisConnectionFailureException("redis down")

        sut.evictPattern("any:*")
    }

    @DisplayName("Redis cache 의 type 이 기대와 불일치하면 (ClassCastException) get 은 null 을 반환한다.")
    @Test
    fun getReturnsNullOnTypeMismatch() {
        every { redisTemplate.opsForValue() } returns valueOps
        // Long 으로 들어있는데 String 으로 요청 — Kotlin `cast` 가 ClassCastException
        every { valueOps.get(any()) } returns 123L

        val result = sut.get("any:key", String::class.java)

        assertThat(result).isNull()
    }
}
