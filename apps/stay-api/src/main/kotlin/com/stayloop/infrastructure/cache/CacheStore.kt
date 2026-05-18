package com.stayloop.infrastructure.cache

import java.time.Duration

/**
 * cache 추상 — `RedisCacheStore` 운영 구현 + `InMemoryCacheStore` 테스트 더블 + Phase M 의 비교군
 * (C-0/C-1/C-2/I-2 put-after-write/L1+L2) 다형성 진입점.
 *
 * 모든 cache 호출 site (`PropertyFacade` / `WishlistFacade` / 향후 `ReservationFacade`) 는 본 인터페이스만 알고,
 * 구체 구현 (`RedisCacheStore` / `InMemoryCacheStore` / 비교군) 은 DI 로 주입.
 *
 * 시그니처 정합:
 * - [get] — cache hit/miss 판정만. miss = `null`. fallback 책임은 호출자.
 * - [getOrPut] — cache-aside 표준. miss 시 loader 호출 + cache put. Redis 다운 시 loader 결과 그대로 반환
 *   (graceful degradation).
 * - [put] — 외부 write 흐름 후 cache 갱신용 (I-2 put-after-write 비교군 사용).
 * - [evict] / [evictPattern] — cache 무효화. Facade 의 `afterCommit` 흐름에서만 호출.
 */
interface CacheStore {
    fun <T : Any> get(key: String, type: Class<T>): T?

    fun <T : Any> getOrPut(key: String, ttl: Duration, type: Class<T>, loader: () -> T): T

    fun <T : Any> put(key: String, value: T, ttl: Duration)

    fun evict(key: String)

    fun evictPattern(pattern: String)
}
