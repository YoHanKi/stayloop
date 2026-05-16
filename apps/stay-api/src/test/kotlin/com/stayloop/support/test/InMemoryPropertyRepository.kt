package com.stayloop.support.test

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.common.value.PageResult
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.PropertyRepository
import com.stayloop.domain.property.value.PropertySortKey
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType

/**
 * 테스트용 InMemory `PropertyRepository`. 운영 RepositoryImpl 과 *의미론 동치* (verify-code §19-A).
 *
 * **PRICE_ASC cross-repo 협조** — 운영 SQL 의 `JOIN room_types JOIN daily_room_rates` 를 InMemory 가
 * 재현하려면 `RoomTypeRepository` + `DailyRoomRateRepository` 의 store 에 접근해야 한다. 생성자 옵션 인자로
 * 주입 받고, 미주입 상태에서 PRICE_ASC 호출 시 명시적 예외 — *조용한 잘못된 결과* 보다 *명시적 실패* 우선.
 *
 * **PRICE_ASC race window 한계** — 운영 RepositoryImpl 의 PRICE_ASC 는 *2 round-trip* (sortedIds fetch →
 * findAllById). 두 round-trip 사이에 다른 thread 가 Property 를 삭제하면 byId[id] = null 로 결과에서 누락되어
 * *page.size 미달* 발생. 본 InMemory 는 *1-pass in-memory filter* 라 race window 0 — 운영보다 *더 안전하게*
 * 동작한다. 결과: *운영의 race window* 가 단위 테스트에 안 잡힘 (verify-code R9 — InMemory 동시성 시뮬레이션
 * 본질적 한계). 운영의 race 영역은 Testcontainers + 부하 테스트에서 검증. PR3 projection 1쿼리 합류 시 race
 * window 자체가 사라지므로 본 한계도 함께 회수 (`docs/plan/week5.md` D-3).
 */
class InMemoryPropertyRepository(
    private val roomTypeStore: InMemoryRoomTypeRepository? = null,
    private val rateStore: InMemoryDailyRoomRateRepository? = null,
) : PropertyRepository {
    private val store = mutableMapOf<Long, PropertyModel>()
    private var sequence = 0L

    override fun save(property: PropertyModel): PropertyModel {
        if (property.id == 0L) {
            assignId(property, ++sequence)
        }
        store[property.id] = property
        return property
    }

    override fun findById(id: Long): PropertyModel? = store[id]

    /**
     * 운영 RepositoryImpl 의 `search` 와 동치. sortKey 별 분기는 운영 SQL 의 의미론을 InMemory 로 재현:
     * - RECOMMENDED — id ASC
     * - WISHES_DESC — wishCount DESC
     * - RATING_DESC — rating.value DESC
     * - PRICE_ASC — JOIN room_types JOIN daily_room_rates → 각 Property 의 min(price_per_night in period)
     *   기준 ASC. `roomTypeStore` / `rateStore` 미주입 시 명시적 IllegalStateException.
     *
     * `total` 은 모든 sort 에서 *city 매칭 row 수* (AC-1 정합).
     * PRICE_ASC 의 K = page.size × 3 candidate overfetch 도 운영과 동일.
     */
    override fun search(
        city: String,
        period: StayPeriod,
        sortKey: PropertySortKey,
        page: PageQuery,
    ): PageResult<PropertyModel> {
        val matched = store.values.filter { it.address.city == city }
        val total = matched.size.toLong()
        val content = if (sortKey == PropertySortKey.PRICE_ASC) {
            searchByPriceAsc(matched, period, page)
        } else {
            val sorted = matched.sortedWith(comparatorForSimpleSort(sortKey))
            val from = page.offset.coerceAtMost(sorted.size)
            val to = (from + page.limit).coerceAtMost(sorted.size)
            sorted.subList(from, to)
        }
        return PageResult(content = content, total = total)
    }

    private fun searchByPriceAsc(
        candidates: List<PropertyModel>,
        period: StayPeriod,
        page: PageQuery,
    ): List<PropertyModel> {
        val roomTypes = requireNotNull(roomTypeStore) {
            "PRICE_ASC 는 InMemoryRoomTypeRepository 주입이 필요하다 (운영 SQL 의 JOIN room_types 재현)."
        }
        val rates = requireNotNull(rateStore) {
            "PRICE_ASC 는 InMemoryDailyRoomRateRepository 주입이 필요하다 (운영 SQL 의 JOIN daily_room_rates 재현)."
        }

        val withMin = candidates.mapNotNull { property ->
            val propertyRoomTypes = roomTypes.findByPropertyId(property.id)
            if (propertyRoomTypes.isEmpty()) return@mapNotNull null
            val ratesInPeriod = propertyRoomTypes.flatMap { rt ->
                rates.findAllInRange(rt.id, period.checkIn, period.checkOut)
            }
            val minPrice = ratesInPeriod.minOfOrNull { it.pricePerNight.amount }
                ?: return@mapNotNull null
            property to minPrice
        }

        val sorted = withMin.sortedBy { it.second }.map { it.first }
        val from = page.offset.coerceAtMost(sorted.size)
        // PRICE_ASC overfetch — K = page.size × 3 (운영 RepositoryImpl 과 동일)
        val to = (from + page.size * PRICE_ASC_CANDIDATE_MULTIPLIER).coerceAtMost(sorted.size)
        return sorted.subList(from, to)
    }

    private fun comparatorForSimpleSort(sortKey: PropertySortKey): Comparator<PropertyModel> = when (sortKey) {
        PropertySortKey.RECOMMENDED -> compareBy { it.id }
        PropertySortKey.WISHES_DESC -> compareByDescending { it.wishCount }
        PropertySortKey.RATING_DESC -> compareByDescending { it.rating.value }
        PropertySortKey.PRICE_ASC ->
            throw CoreException(ErrorType.INTERNAL_ERROR, "PRICE_ASC 는 searchByPriceAsc 에서 처리해야 한다.")
    }

    override fun findAllByIds(ids: Collection<Long>): List<PropertyModel> = ids.mapNotNull { store[it] }

    override fun deleteById(id: Long) {
        store.remove(id)
    }

    /**
     * 운영 RepositoryImpl 의 `UPDATE ... SET wish_count = wish_count + 1 WHERE id = ?` 와 동일 의미론.
     * 미존재 propertyId → affected = 0 (멱등). 존재 → 도메인 메서드 호출 (마지막 방어선) + affected = 1.
     */
    override fun atomicIncrementWishCount(propertyId: Long): Int {
        val property = store[propertyId] ?: return 0
        property.incrementWishCount()
        return 1
    }

    /**
     * 운영 SQL `UPDATE ... SET wish_count = wish_count - 1 WHERE id = ? AND wish_count > 0` 와 동등.
     * `wish_count <= 0` 또는 미존재 → affected = 0 (멱등 noop). 도메인 메서드 (`decrementWishCount`) 의
     * `CONFLICT` throw 동작은 `wish_count > 0` 가드를 *먼저* 통과한 후에만 호출되도록 정렬 — InMemory 가
     * 운영 SQL 의 *조건부 갱신* 의미를 그대로 재현 (verify-code §19-A 운영-테스트 동치).
     */
    override fun atomicDecrementWishCount(propertyId: Long): Int {
        val property = store[propertyId] ?: return 0
        if (property.wishCount <= 0) return 0
        property.decrementWishCount()
        return 1
    }

    private fun assignId(property: PropertyModel, id: Long) {
        val field = BaseEntity::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.setLong(property, id)
    }

    companion object {
        private const val PRICE_ASC_CANDIDATE_MULTIPLIER: Int = 3
    }
}
