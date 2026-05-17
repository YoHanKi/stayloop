package com.stayloop.infrastructure.cache

import com.stayloop.testcontainers.RedisTestContainersConfig
import com.stayloop.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Duration

/**
 * week5 PR4 A-1 — `RedisCacheStore` Testcontainers 통합 테스트.
 *
 * **검증 의도** — D-4 채택안의 *기반 추상* 검증:
 * - get hit / miss (null) — cache-aside 의 1차 분기 정합
 * - getOrPut — miss 시 loader 호출 + cache put, hit 시 loader 미호출
 * - put / get round-trip — Jackson 직렬화 정합
 * - evict / evictPattern — 무효화 정합 + KEYS 동작
 * - TTL expiry — sleep 기반 (CI 환경에서 flake 위험은 매우 짧은 TTL 로 완화)
 *
 * **Redis 다운 fallback 의 본격 검증은 Phase A-6 통합 테스트** — 본 테스트는 정상 흐름만 검증.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestContainersConfig::class)
class RedisCacheStoreTest {
    @Autowired
    private lateinit var sut: RedisCacheStore

    @Autowired
    private lateinit var redisCleanUp: RedisCleanUp

    @BeforeEach
    fun cleanUp() {
        redisCleanUp.truncateAll()
    }

    @AfterEach
    fun tearDown() {
        redisCleanUp.truncateAll()
    }

    @DisplayName("get 은 cache miss 시 null 을 반환한다.")
    @Test
    fun getReturnsNullOnMiss() {
        val result = sut.get("missing:key", SamplePayload::class.java)

        assertThat(result).isNull()
    }

    @DisplayName("put 으로 저장한 값을 get 으로 같은 type 으로 조회한다 (Jackson round-trip).")
    @Test
    fun putThenGetRoundTripsJackson() {
        val payload = SamplePayload(id = 42L, name = "강남호텔", count = 7)
        sut.put("sample:42", payload, Duration.ofMinutes(1))

        val result = sut.get("sample:42", SamplePayload::class.java)

        assertThat(result).isEqualTo(payload)
    }

    @DisplayName("getOrPut 은 cache miss 면 loader 를 호출하고 결과를 put 한다.")
    @Test
    fun getOrPutCallsLoaderOnMissAndCachesResult() {
        var loaderCount = 0
        val loaded = sut.getOrPut("sample:miss", Duration.ofMinutes(1), SamplePayload::class.java) {
            loaderCount += 1
            SamplePayload(id = 1L, name = "loaded", count = 1)
        }

        assertThat(loaded).isEqualTo(SamplePayload(id = 1L, name = "loaded", count = 1))
        assertThat(loaderCount).isEqualTo(1)

        // 두 번째 호출은 cache hit — loader 미호출
        val cached = sut.getOrPut("sample:miss", Duration.ofMinutes(1), SamplePayload::class.java) {
            loaderCount += 1
            SamplePayload(id = 99L, name = "should-not-be-called", count = 99)
        }
        assertThat(cached).isEqualTo(SamplePayload(id = 1L, name = "loaded", count = 1))
        assertThat(loaderCount).isEqualTo(1)
    }

    @DisplayName("evict 는 단일 key 를 무효화하고, 후속 get 은 null 을 반환한다.")
    @Test
    fun evictRemovesSingleKey() {
        sut.put("sample:evict", SamplePayload(id = 1L, name = "x", count = 1), Duration.ofMinutes(1))
        assertThat(sut.get("sample:evict", SamplePayload::class.java)).isNotNull

        sut.evict("sample:evict")

        assertThat(sut.get("sample:evict", SamplePayload::class.java)).isNull()
    }

    @DisplayName("evictPattern 은 prefix 매칭 key 들을 한번에 무효화한다 (KEYS O(N) 한계 박제).")
    @Test
    fun evictPatternRemovesMatchingKeys() {
        sut.put("availability:rt1:20260601", SamplePayload(id = 1L, name = "a", count = 1), Duration.ofMinutes(1))
        sut.put("availability:rt1:20260602", SamplePayload(id = 2L, name = "b", count = 1), Duration.ofMinutes(1))
        sut.put("availability:rt2:20260601", SamplePayload(id = 3L, name = "c", count = 1), Duration.ofMinutes(1))
        sut.put("other:key", SamplePayload(id = 4L, name = "d", count = 1), Duration.ofMinutes(1))

        sut.evictPattern("availability:rt1:*")

        // rt1 의 두 키만 사라짐. rt2 / other 은 남음.
        assertThat(sut.get("availability:rt1:20260601", SamplePayload::class.java)).isNull()
        assertThat(sut.get("availability:rt1:20260602", SamplePayload::class.java)).isNull()
        assertThat(sut.get("availability:rt2:20260601", SamplePayload::class.java)).isNotNull
        assertThat(sut.get("other:key", SamplePayload::class.java)).isNotNull
    }

    @DisplayName("put 의 TTL 이 만료되면 get 은 null 을 반환한다.")
    @Test
    fun ttlExpiry() {
        sut.put("sample:ttl", SamplePayload(id = 1L, name = "x", count = 1), Duration.ofMillis(200))
        assertThat(sut.get("sample:ttl", SamplePayload::class.java)).isNotNull

        Thread.sleep(400)

        assertThat(sut.get("sample:ttl", SamplePayload::class.java)).isNull()
    }

    /**
     * GenericJackson2JsonRedisSerializer 가 `@class` type info 를 보존하는지 검증용. property 클래스가
     * `data class` 라 equals 가 컨텐츠 비교.
     */
    data class SamplePayload(val id: Long, val name: String, val count: Int)
}
