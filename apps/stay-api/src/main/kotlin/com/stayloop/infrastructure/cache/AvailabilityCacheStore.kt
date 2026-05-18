package com.stayloop.infrastructure.cache

import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * RT × date 단위 가용성 cache. (`docs/plan/week5.md` PR4 A-5, D-4)
 *
 * **본 store 의 적합 진입점**: `PropertyFacade.getAvailableRooms` 의 *검색 후 클릭 → 가용 조회* 흐름.
 * 단기 (10s) 동적 cache — 같은 사용자가 같은 RT × 같은 일자 가용성을 짧은 시간 내 반복 조회하는 패턴.
 *
 * **결제 흐름 금지 (D-5 contract)**: `ReservationFacade.reserve` 는 *비관적 락 (DB 행 직접 락)* 을 잡으므로
 * 본 cache 를 *결정 근거로 사용 금지*. cache hit 으로 "재고 있음" 응답을 받았더라도 *실제 차감* 은 DB 행
 * 락 직후 재확인. 이 contract 가 깨지면 *더블부킹 가능* (Loop 8 §고민 의 직접 위협).
 *
 * **무효화 (evict) 의무**: `ReservationFacade.reserve` / `cancel` 이 inventory 를 변경한 직후 *afterCommit*
 * 에서 변경된 RT × dates 들의 cache 를 evict. cache stale 이 결제 후 검색 결과로 누출되는 사고 차단.
 *
 * **키 형식**: `availability:{roomTypeId}:{yyyyMMdd}` — date 별 개별 키. evict 는 *개별 키 삭제* (KEYS O(N)
 * 회피, `evictPattern` 미사용).
 *
 * **TTL = 10s**: Loop 7 §고민 3 의 *짧은 TTL* — 더블부킹 위험을 줄이고 stale 데이터 노출 시간 제한. 운영
 * 합류 시 부하 측정으로 재조정 (Phase M 비교군 측정).
 *
 * **API 설계**:
 * - [loadForRange] — cache-aside batch. 일부 date 는 cache hit, 일부는 miss → loader 가 *miss 인 date 만*
 *   DB 에서 fetch + cache put. 호출자가 *모든 date* 의 결과를 받음.
 * - [evictForDates] — 특정 RT × date 목록 개별 evict.
 */
@Component
class AvailabilityCacheStore(
    private val cacheStore: CacheStore,
) {
    companion object {
        private const val KEY_PREFIX = "availability:"
        private val TTL: Duration = Duration.ofSeconds(10)
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
    }

    /**
     * `[from, to)` 반-닫힌 구간의 *각 일자별* 가용성 cache 를 통과한다. miss 인 일자만 [loader] 에 전달
     * 되어 *batch fetch* — 호출자는 loader 가 *해당 일자 분의 데이터를 한 번에 가져오는 비용* 만 부담.
     *
     * Loader 가 반환하지 않은 일자는 결과에 포함되지 않음 (운영 inventory 가 누락된 경우 — `findAllInRange`
     * 의 *누락 일자 미포함* 의미론과 정합).
     *
     * @return 모든 일자에 대해 cache 또는 loader 가 채운 가용성 row. 일부 누락 가능.
     */
    fun loadForRange(
        roomTypeId: Long,
        from: LocalDate,
        to: LocalDate,
        loader: (missingDates: List<LocalDate>) -> List<RoomDailyAvailability>,
    ): List<RoomDailyAvailability> {
        if (!from.isBefore(to)) return emptyList()

        val dates = generateSequence(from) { it.plusDays(1) }
            .takeWhile { it.isBefore(to) }
            .toList()

        val hits = mutableMapOf<LocalDate, RoomDailyAvailability>()
        val misses = mutableListOf<LocalDate>()
        for (date in dates) {
            val cached = cacheStore.get(keyOf(roomTypeId, date), RoomDailyAvailability::class.java)
            if (cached != null) {
                hits[date] = cached
            } else {
                misses += date
            }
        }

        if (misses.isNotEmpty()) {
            val loaded = loader(misses)
            for (row in loaded) {
                cacheStore.put(keyOf(row.roomTypeId, row.date), row, TTL)
                hits[row.date] = row
            }
        }

        return dates.mapNotNull { hits[it] }
    }

    /**
     * 특정 RT × `dates` 의 cache 를 *개별 evict*. `ReservationFacade.reserve` / `cancel` 의 afterCommit 에서
     * 호출.
     *
     * `evictPattern` 미사용 — *KEYS O(N) latency spike* 위험 회피 (PR4 RedisCacheStore KDoc 정합).
     */
    fun evictForDates(roomTypeId: Long, dates: Collection<LocalDate>) {
        for (date in dates) {
            cacheStore.evict(keyOf(roomTypeId, date))
        }
    }

    private fun keyOf(roomTypeId: Long, date: LocalDate): String =
        KEY_PREFIX + roomTypeId + ":" + date.format(DATE_FORMAT)
}
