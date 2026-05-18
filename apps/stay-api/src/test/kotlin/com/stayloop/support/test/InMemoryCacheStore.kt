package com.stayloop.support.test

import com.stayloop.infrastructure.cache.CacheStore
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 테스트용 InMemory `CacheStore`. 운영 `RedisCacheStore` 와 *의미론 동치* (verify-code §19-A).
 *
 * **단위 테스트 기본 모드 — TTL 무시 / hit-miss 만 검증**: PropertyFacade 등 application 흐름의 단위
 * 테스트는 *cache hit/miss 분기가 정합* 한지만 본다. TTL 만료는 통합 테스트 (Testcontainers Redis) 가
 * 검증.
 *
 * **TTL 활성화 모드 — `Clock` 주입**: 일부 테스트가 *TTL 만료 동작* 을 단위 레벨에서 검증해야 하면
 * 생성자에 `Clock` 을 주입. 기본은 `Clock.systemDefaultZone()` — 실제 시간 흐름.
 *
 * **운영 동치 정책**:
 * - `get` miss = `null`
 * - `getOrPut` miss 시 loader 호출 + put. Hit 시 loader 미호출 (호출 횟수 검증 가능)
 * - `evict` / `evictPattern` 즉시 적용
 * - Redis 다운 fallback 시나리오는 InMemory 에 적용 X — 통합 테스트 책임 (§19-A 영구 한계 박제)
 */
class InMemoryCacheStore(
    private val clock: Clock = Clock.systemDefaultZone(),
) : CacheStore {
    private data class Entry(val value: Any, val expiresAt: Instant)

    private val store = mutableMapOf<String, Entry>()

    override fun <T : Any> get(key: String, type: Class<T>): T? {
        val entry = store[key] ?: return null
        if (entry.expiresAt.isBefore(Instant.now(clock))) {
            store.remove(key)
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return entry.value as? T
    }

    override fun <T : Any> getOrPut(key: String, ttl: Duration, type: Class<T>, loader: () -> T): T {
        val cached = get(key, type)
        if (cached != null) return cached
        val loaded = loader()
        store[key] = Entry(loaded, Instant.now(clock).plus(ttl))
        return loaded
    }

    override fun <T : Any> put(key: String, value: T, ttl: Duration) {
        store[key] = Entry(value, Instant.now(clock).plus(ttl))
    }

    override fun evict(key: String) {
        store.remove(key)
    }

    override fun evictPattern(pattern: String) {
        val regex = pattern.replace(".", "\\.").replace("*", ".*").toRegex()
        val keys = store.keys.filter { regex.matches(it) }
        keys.forEach { store.remove(it) }
    }
}
