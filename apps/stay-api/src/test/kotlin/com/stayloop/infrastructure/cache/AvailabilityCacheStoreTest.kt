package com.stayloop.infrastructure.cache

import com.stayloop.support.test.InMemoryCacheStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * week5 PR4 A-5 — `AvailabilityCacheStore` 단위 동작 검증.
 *
 * **검증 의도** — D-4 채택안의 *RT × date 단위 cache 정합*:
 * - 첫 호출 = 모든 일자 miss → loader 가 모든 일자 fetch + cache put
 * - 두 번째 호출 = 모든 일자 hit → loader 호출 없음
 * - `evictForDates` 후 호출 = 해당 일자만 miss → loader 가 *miss 인 일자만* fetch (개별 키 evict 의 본질 박제)
 *
 * Redis 다운 fallback / TTL 만료는 통합 테스트 (`RedisCacheStoreTest`) 가 검증 — 본 테스트는 *호출 흐름 정합*
 * 만 책임.
 */
class AvailabilityCacheStoreTest {
    private val cacheStore = InMemoryCacheStore()
    private val sut = AvailabilityCacheStore(cacheStore)

    private val roomTypeId = 1L
    private val from = LocalDate.of(2026, 6, 1)
    private val to = LocalDate.of(2026, 6, 4)
    private val dates = listOf(
        LocalDate.of(2026, 6, 1),
        LocalDate.of(2026, 6, 2),
        LocalDate.of(2026, 6, 3),
    )

    @DisplayName("loadForRange 첫 호출은 모든 일자 miss — loader 가 모든 일자 fetch 한다.")
    @Test
    fun firstCallLoadsAllDatesViaLoader() {
        var loaderInvocations = 0
        val captured = mutableListOf<List<LocalDate>>()

        val result = sut.loadForRange(roomTypeId, from, to) { missing ->
            loaderInvocations += 1
            captured += missing
            missing.map { RoomDailyAvailability(roomTypeId, it, totalRooms = 5, reservedRooms = 0, pricePerNight = 80_000) }
        }

        assertThat(loaderInvocations).isEqualTo(1)
        assertThat(captured.single()).containsExactlyElementsOf(dates)
        assertThat(result.map { it.date }).containsExactlyElementsOf(dates)
        assertThat(result.map { it.availableRooms }).containsOnly(5)
    }

    @DisplayName("loadForRange 두 번째 호출은 모든 일자 hit — loader 가 호출되지 않는다.")
    @Test
    fun secondCallHitsCacheCompletely() {
        // 사전 cache 적재
        sut.loadForRange(roomTypeId, from, to) { missing ->
            missing.map { RoomDailyAvailability(roomTypeId, it, 5, 0, 80_000) }
        }

        var loaderInvocations = 0
        val result = sut.loadForRange(roomTypeId, from, to) { missing ->
            loaderInvocations += 1
            missing.map { RoomDailyAvailability(roomTypeId, it, 999, 999, 999) }
        }

        assertThat(loaderInvocations).isEqualTo(0)
        assertThat(result.map { it.availableRooms }).containsOnly(5)
    }

    @DisplayName("evictForDates 후 호출은 evict 된 일자만 miss 로 처리된다 (개별 키 evict 의 KEYS O(N) 회피).")
    @Test
    fun evictForDatesRemovesOnlyTargetedKeys() {
        // 3 일자 모두 cache 적재
        sut.loadForRange(roomTypeId, from, to) { missing ->
            missing.map { RoomDailyAvailability(roomTypeId, it, 5, 0, 80_000) }
        }

        // 가운데 일자만 evict
        val evictTarget = LocalDate.of(2026, 6, 2)
        sut.evictForDates(roomTypeId, listOf(evictTarget))

        var loaderMissing: List<LocalDate>? = null
        sut.loadForRange(roomTypeId, from, to) { missing ->
            loaderMissing = missing
            missing.map { RoomDailyAvailability(roomTypeId, it, 1, 0, 90_000) }
        }

        // 가운데 일자만 miss — loader 에 그 일자만 전달됨
        assertThat(loaderMissing).containsExactly(evictTarget)
    }

    @DisplayName("loader 가 *일부 일자만* 반환하면 (운영 inventory 누락 시나리오) 결과는 누락 일자 제외.")
    @Test
    fun missingDatesAreOmittedFromResult() {
        val result = sut.loadForRange(roomTypeId, from, to) { missing ->
            // 가운데 일자만 반환 — 6/1, 6/3 누락
            missing.filter { it == LocalDate.of(2026, 6, 2) }
                .map { RoomDailyAvailability(roomTypeId, it, 5, 0, 80_000) }
        }

        assertThat(result.map { it.date }).containsExactly(LocalDate.of(2026, 6, 2))
    }

    @DisplayName("from == to 빈 구간은 loader 호출 없이 빈 결과.")
    @Test
    fun emptyRangeReturnsEmptyWithoutLoader() {
        var loaderInvocations = 0
        val result = sut.loadForRange(roomTypeId, from, from) { _ ->
            loaderInvocations += 1
            emptyList()
        }

        assertThat(loaderInvocations).isEqualTo(0)
        assertThat(result).isEmpty()
    }
}
