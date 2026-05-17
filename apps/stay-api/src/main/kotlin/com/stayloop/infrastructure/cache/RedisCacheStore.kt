package com.stayloop.infrastructure.cache

import com.stayloop.config.CacheRedisConfig
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * PR4 D-4 채택안의 *cache-aside + fallback* 추상.
 *
 * **모든 cache 호출 site 의 단일 진입점**: Facade 는 `RedisTemplate` / `@Cacheable` / `RedisCacheManager` 를
 * 직접 알지 않는다 — 본 store 를 통해서만 cache 접근. AOP `@Cacheable` 의 *호출 site 가 보이지 않는 추상*
 * 대신 *명시적 호출* 우위 (week5-b.md Loop 7 §고민 4).
 *
 * **Fallback 의무 (week5.md Q5 정합)**: Redis 다운 (`DataAccessException`) 시 *서비스가 죽으면 안 됨* —
 * `get` / `getOrPut` 모두 `log.warn` + `fallback()` 결과를 *그대로 반환*. fallback 결과는 cache 에 다시
 * put 하지 않는다 (cache 자체가 다운된 상태이므로 *재진입 시도가 의미 없음*).
 *
 * **TX commit 후 evict 강제 (week5.md PR4 A-3 / A-5)**: 본 store 자체는 *동기 호출* — TX 안에서 `evict()`
 * 를 호출하면 *race window* 발생 (evict 후 DB rollback 시 cache-원본 불일치). Facade 는
 * `TransactionSynchronizationManager.registerSynchronization` 의 `afterCommit` 에서만 evict 호출.
 *
 * **`evictPattern` 의 KEYS O(N) 한계**: Redis 의 `KEYS pattern` 은 *전체 keyspace scan* — production 환경에서
 * 단일 인스턴스라도 latency spike 위험. PR4 의 본 라운드는 *단일 인스턴스 + 소량 cache entry* 가정으로
 * `KEYS` 사용. 운영 합류 시 `SCAN` (cursor-based) 으로 전환 (week6+ 인계). `evictPattern` 호출은 admin /
 * Reservation commit 흐름만, 일반 read/write 경로에서 호출 X.
 *
 * **단일 인스턴스 가정 (week5.md "단일 인스턴스 가정")**: 다중 pod / Redis Cluster 는 본 라운드 외.
 * 다중 pod 합류 시 *L1 (Caffeine) 추가 + pub/sub invalidation* 이 필수 항목으로 떠오름 — 본 store 의
 * `evict` 가 *전 pod 에 전파* 되어야 정합. 본 라운드 비교군 (Phase M) 에서 측정 후 결정.
 *
 * **시그니처 설계**:
 * - [get] — cache hit/miss 만 판정. miss = `null`. fallback 책임은 호출자.
 * - [getOrPut] — *cache-aside 표준 패턴*. miss 시 `loader` 호출 + 결과를 cache 에 put. Redis 다운 시
 *   `loader` 결과를 반환하되 put 시도 X.
 * - [put] — 외부 write 흐름 후 cache 갱신용 (put-after-write 비교군 I-2 가 사용). 본 라운드 채택안 (I-1
 *   evict-only) 은 사용 X — `getOrPut` 의 내부 put 만 활성.
 * - [evict] / [evictPattern] — cache 무효화. afterCommit 에서만 호출.
 */
@Component
class RedisCacheStore(
    @Qualifier(CacheRedisConfig.CACHE_REDIS_TEMPLATE)
    private val redisTemplate: RedisTemplate<String, Any>,
) {
    private val log = LoggerFactory.getLogger(RedisCacheStore::class.java)

    /**
     * key 의 cache value 를 type [T] 로 조회. miss / Redis 다운 시 `null`.
     *
     * Redis 다운 시 `log.warn` 후 `null` — fallback 책임은 호출자 (보통 [getOrPut] 사용 권장).
     */
    fun <T : Any> get(key: String, type: Class<T>): T? {
        return try {
            val raw = redisTemplate.opsForValue().get(key) ?: return null
            type.cast(raw)
        } catch (e: DataAccessException) {
            log.warn("Redis get 실패 (fallback to null). key={}", key, e)
            null
        } catch (e: ClassCastException) {
            log.warn("Redis cache 의 type 이 기대와 불일치. key={}, expected={}", key, type.simpleName, e)
            null
        }
    }

    /**
     * Cache-aside 표준. cache hit 면 그 값을, miss 면 [loader] 호출 결과를 cache 에 [ttl] 로 put 후 반환.
     *
     * Redis 다운 시: `log.warn` + `loader()` 결과 반환 (cache put 시도 X). 서비스가 죽지 않고 매 요청 DB
     * 로 빠지는 *graceful degradation* 박제.
     */
    fun <T : Any> getOrPut(key: String, ttl: Duration, type: Class<T>, loader: () -> T): T {
        val cached = get(key, type)
        if (cached != null) return cached

        val loaded = loader()
        try {
            redisTemplate.opsForValue().set(key, loaded, ttl)
        } catch (e: DataAccessException) {
            log.warn("Redis put 실패 (fallback: 캐시 미사용). key={}", key, e)
        }
        return loaded
    }

    /**
     * 외부 write 흐름의 *put-after-write* 갱신. *PR4 채택안 (I-1 evict-only) 은 사용 X* — Phase M 비교군
     * (I-2 put-after-write) 측정용으로만 노출. Redis 다운 시 silent fallback (`log.warn`).
     */
    fun <T : Any> put(key: String, value: T, ttl: Duration) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl)
        } catch (e: DataAccessException) {
            log.warn("Redis put 실패. key={}", key, e)
        }
    }

    /**
     * 단일 key 무효화. Facade 의 `afterCommit` 흐름에서만 호출.
     */
    fun evict(key: String) {
        try {
            redisTemplate.delete(key)
        } catch (e: DataAccessException) {
            log.warn("Redis evict 실패. key={}", key, e)
        }
    }

    /**
     * Pattern 기반 무효화 (`KEYS` + `DEL`). **운영 latency 위험** — KDoc 의 *KEYS O(N) 한계* 박제.
     * Reservation commit 후 `availability:{rt}:*` 같은 *제한된 prefix* 만 호출.
     */
    fun evictPattern(pattern: String) {
        try {
            val keys = redisTemplate.keys(pattern)
            if (keys.isNotEmpty()) {
                redisTemplate.delete(keys)
            }
        } catch (e: DataAccessException) {
            log.warn("Redis evictPattern 실패. pattern={}", pattern, e)
        }
    }
}
