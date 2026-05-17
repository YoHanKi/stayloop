package com.stayloop.infrastructure.property

import com.querydsl.core.types.OrderSpecifier
import com.querydsl.core.types.dsl.Expressions
import com.querydsl.jpa.impl.JPAQueryFactory
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.common.value.PageResult
import com.stayloop.domain.inventory.QDailyRoomInventoryModel
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.PropertyRepository
import com.stayloop.domain.property.PropertySearchRow
import com.stayloop.domain.property.QPropertyModel
import com.stayloop.domain.property.QRoomTypeModel
import com.stayloop.domain.property.value.PropertySortKey
import com.stayloop.domain.rate.QDailyRoomRateModel
import com.stayloop.domain.reservation.value.StayPeriod
import org.springframework.stereotype.Component

@Component
class PropertyRepositoryImpl(
    private val propertyJpaRepository: PropertyJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : PropertyRepository {
    override fun save(property: PropertyModel): PropertyModel = propertyJpaRepository.save(property)

    override fun findById(id: Long): PropertyModel? = propertyJpaRepository.findById(id).orElse(null)

    /**
     * week5 PR1 D-6 — 4종 sort 모두 활성. 본 메서드의 SQL 분기는 V010 의 복합 인덱스 정합:
     * - RECOMMENDED / WISHES_DESC / RATING_DESC — `properties` 단일 테이블 `ORDER BY` (각각 PK / (city, wish_count DESC) /
     *   (city, rating DESC) prefix scan, filesort 없음).
     * - PRICE_ASC — `properties JOIN room_types JOIN daily_room_rates` + GROUP BY p.id + ORDER BY MIN(price_per_night)
     *   ASC. `daily_room_rates` 의 V010 `(room_type_id, date)` 인덱스 prefix scan 정합.
     *
     * PRICE_ASC 만 K = `page.size * 3` candidate overfetch — Facade 가 후속 가용성 N+1 필터 후 page.size take.
     * 본 PageResult.content.size 는 *최대 size×3* (다른 sort 는 정확히 page.size 또는 그 이하).
     *
     * `total` 은 모든 sort 에서 *city 매칭 row 수* — AC-1 정합 (가용성은 응답 시점 일시적 상태, total 의미 안 섞음).
     */
    override fun search(
        city: String,
        period: StayPeriod,
        sortKey: PropertySortKey,
        page: PageQuery,
    ): PageResult<PropertyModel> {
        val p = QPropertyModel.propertyModel
        val cityCondition = p.address.city.eq(city)

        val total = queryFactory
            .select(p.count())
            .from(p)
            .where(cityCondition)
            .fetchOne() ?: 0L

        val content = if (sortKey == PropertySortKey.PRICE_ASC) {
            searchByPriceAsc(city, period, page)
        } else {
            queryFactory
                .selectFrom(p)
                .where(cityCondition)
                .orderBy(*orderSpecifierForSimpleSort(sortKey, p))
                .offset(page.offset.toLong())
                .limit(page.size.toLong())
                .fetch()
        }

        return PageResult(content = content, total = total)
    }

    /**
     * PRICE_ASC 의 hybrid 구현 — SQL JOIN GROUP BY MIN 으로 ID 정렬, 그 후 `findAllById` 로 hydrate.
     *
     * 2 round-trip 인 이유: `SELECT p.*, MIN(...) FROM ... GROUP BY p.id` 는 MySQL strict mode
     * (ONLY_FULL_GROUP_BY) 에서 *p.id 외 모든 컬럼을 GROUP BY 에 추가* 해야 합법. 그러면 GROUP BY 비용 폭증.
     * subquery → ID 만 정렬 → 별도 fetch 가 *index lookup* 으로 가벼움 (`idx_properties_city`).
     *
     * **K = page.size × 3 overfetch**: PR1 의 hybrid 결정 (week5-b.md L3 ↔ decompose-decision Q4 박제).
     * Facade 가 후속 가용성 필터 후 page.size 만큼 take. 부족 시 그대로 반환 (δ — OFFSET 깊은 페이지 한계
     * week6+ 인계 정합).
     */
    private fun searchByPriceAsc(
        city: String,
        period: StayPeriod,
        page: PageQuery,
    ): List<PropertyModel> {
        val p = QPropertyModel.propertyModel
        val rt = QRoomTypeModel.roomTypeModel
        val rate = QDailyRoomRateModel.dailyRoomRateModel

        val sortedIds = queryFactory
            .select(p.id)
            .from(p)
            .innerJoin(rt).on(rt.propertyId.eq(p.id))
            .innerJoin(rate).on(rate.roomTypeId.eq(rt.id))
            .where(
                p.address.city.eq(city),
                rate.date.goe(period.checkIn),
                rate.date.lt(period.checkOut),
            )
            .groupBy(p.id)
            // pricePerNight 은 Money @Embedded VO 이고 컬럼은 amount (= price_per_night). QueryDSL path 는
            // pricePerNight.amount 로 Long 컬럼 expression. min() → NumberExpression 의 asc() 발화.
            .orderBy(rate.pricePerNight.amount.min().asc())
            .offset(page.offset.toLong())
            .limit((page.size * PRICE_ASC_CANDIDATE_MULTIPLIER).toLong())
            .fetch()

        if (sortedIds.isEmpty()) return emptyList()

        val byId = propertyJpaRepository.findAllById(sortedIds).associateBy { it.id }
        // SQL 의 정렬 순서를 보존 — findAllById 는 PK 순으로 반환할 수 있어 mapNotNull 로 ID 순서 강제.
        return sortedIds.mapNotNull { byId[it] }
    }

    /**
     * PRICE_ASC 외 sort 의 OrderSpecifier. V010 의 복합 인덱스와 1:1 정합.
     */
    private fun orderSpecifierForSimpleSort(
        sortKey: PropertySortKey,
        p: QPropertyModel,
    ): Array<OrderSpecifier<*>> = when (sortKey) {
        PropertySortKey.RECOMMENDED -> arrayOf(p.id.asc())
        PropertySortKey.WISHES_DESC -> arrayOf(p.wishCount.desc())
        PropertySortKey.RATING_DESC -> arrayOf(p.rating.value.desc())
        PropertySortKey.PRICE_ASC -> error("PRICE_ASC 는 searchByPriceAsc 에서 처리해야 한다.")
    }

    /**
     * week5 PR3 D-3 — N+1 제거 검색 (QueryDSL 2-step batch IN, 총 4 SQL).
     *
     * **2-step 채택 사유 (Loop 7''/8'' 박제)**: 초기 plan 의 *N-2 1쿼리 projection* 가설은 *MIN(SUM(...))*
     * 의 2-level aggregation 을 단일 쿼리로 표현 — `LIMIT 20` 인데도 inner 가 city 1800 properties 의 *전체*
     * 집계를 수행하는 *낭비*. GR-3 절차 발동 후 **N-3 batch IN** 으로 재채택: candidate ID 사전 추출 + IN list
     * 로 좁혀진 aggregation. 측정: 91ms p95 (vs 1쿼리 1.43s).
     *
     * **흐름**:
     * 1. Step 1 — `idx_properties_city*` 로 K = page.size × 3 candidate IDs 추출 (사전 가용 후보).
     * 2. Step 2 — Step 1 의 K IDs 로 per-RT aggregation. INNER JOIN inventory 로 가용성 검증 +
     *    HAVING COUNT(*) = nights 로 *모든 일자 가용* 가드.
     * 3. Step 2.5 — 인메모리 GROUP BY propertyId: `MIN(total)` + `COUNT(rt)` 계산.
     * 4. Step 3 — eligible Property entity fetch (sort 순서 보존 또는 PRICE_ASC 시 lowest_total 재정렬).
     *
     * **인덱스 정합** (V010 + V001):
     * - Step 1: `idx_properties_city` / `idx_properties_city_wish_count` / `idx_properties_city_rating`
     * - Step 2: `rt.property_id IN (...)` → `idx_room_types_property_id` → `idx_daily_room_rates_room_type_date`
     *   → `daily_room_inventories.PRIMARY` (eq_ref)
     * - Step 3: `propertyJpaRepository.findAllById` → PRIMARY
     *
     * **K = page.size × 3 overfetch**: Step 1 의 candidate 중 일부가 가용 0 (HAVING COUNT 미충족) 으로 Step 2
     * 탈락. 보수적으로 ×3 — *대부분 page.size 충족*. δ (page.size 미달) 케이스는 영구 한계 박제.
     *
     * **JdbcTemplate 미사용**: 모든 쿼리가 QueryDSL `JPAQueryFactory` 로 표현. 컴파일 시점 컬럼 오타 차단 +
     * Q-class 갱신 시 SQL 자동 정합. `PropertySearchRow` 는 plain Kotlin (JPA 누출 0).
     */
    override fun searchInfos(
        city: String,
        period: StayPeriod,
        sortKey: PropertySortKey,
        guestCount: Int,
        page: PageQuery,
    ): PageResult<PropertySearchRow> {
        val p = QPropertyModel.propertyModel
        val total = queryFactory
            .select(p.count())
            .from(p)
            .where(p.address.city.eq(city))
            .fetchOne() ?: 0L

        if (total == 0L) return PageResult(content = emptyList(), total = 0L)

        val k = page.size * SEARCH_CANDIDATE_MULTIPLIER
        val candidateIds = fetchCandidateIds(city, period, sortKey, guestCount, k.toLong(), page.offset.toLong())
        if (candidateIds.isEmpty()) return PageResult(content = emptyList(), total = total)

        val perProperty = aggregatePerProperty(candidateIds, period, guestCount)
        if (perProperty.isEmpty()) return PageResult(content = emptyList(), total = total)

        // sort 보존 — RECOMMENDED/WISHES_DESC/RATING_DESC 는 Step 1 의 순서, PRICE_ASC 는 Step 2 의 lowest_total 기준 재정렬.
        val orderedEligibleIds = if (sortKey == PropertySortKey.PRICE_ASC) {
            candidateIds.filter { it in perProperty }
                .sortedWith(compareBy({ perProperty.getValue(it).lowestTotal }, { it }))
        } else {
            candidateIds.filter { it in perProperty }
        }
        val pageIds = orderedEligibleIds.take(page.size)
        if (pageIds.isEmpty()) return PageResult(content = emptyList(), total = total)

        val byId = propertyJpaRepository.findAllById(pageIds).associateBy { it.id }
        val content = pageIds.mapNotNull { id ->
            val property = byId[id] ?: return@mapNotNull null
            val agg = perProperty[id] ?: return@mapNotNull null
            toSearchRow(property, agg)
        }
        return PageResult(content = content, total = total)
    }

    /**
     * Step 1 — candidate ID 추출 (QueryDSL). sortKey 별로 다른 ORDER BY.
     *
     * 모든 sort 에서 *rate 존재 + max_guests* 사전 필터 — Step 2 의 inventory 까지 가는 비용을 *후보 풀* 단계에서
     * 줄임. Step 2 의 inventory + HAVING 가 *정확한* 가용성 재검증.
     *
     * PRICE_ASC 만 `rate.pricePerNight.amount.min()` 으로 ORDER BY — 가격 후보군 사전 추출. Step 2 후 *실제
     * 합산 최저가* 로 재정렬 (per-night MIN ≠ per-period SUM 의 미세 차이 보정).
     */
    private fun fetchCandidateIds(
        city: String,
        period: StayPeriod,
        sortKey: PropertySortKey,
        guestCount: Int,
        limit: Long,
        offset: Long,
    ): List<Long> {
        val p = QPropertyModel.propertyModel
        val rt = QRoomTypeModel.roomTypeModel
        val rate = QDailyRoomRateModel.dailyRoomRateModel

        val orderSpecs: Array<OrderSpecifier<*>> = when (sortKey) {
            PropertySortKey.RECOMMENDED -> arrayOf(p.id.asc())
            PropertySortKey.WISHES_DESC -> arrayOf(p.wishCount.desc(), p.id.asc())
            PropertySortKey.RATING_DESC -> arrayOf(p.rating.value.desc(), p.id.asc())
            PropertySortKey.PRICE_ASC -> arrayOf(rate.pricePerNight.amount.min().asc(), p.id.asc())
        }

        return queryFactory
            .select(p.id)
            .from(p)
            .innerJoin(rt).on(rt.propertyId.eq(p.id))
            .innerJoin(rate).on(rate.roomTypeId.eq(rt.id))
            .where(
                p.address.city.eq(city),
                rate.date.goe(period.checkIn),
                rate.date.lt(period.checkOut),
                rt.guestCount.max.goe(guestCount),
            )
            .groupBy(p.id)
            .orderBy(*orderSpecs)
            .offset(offset)
            .limit(limit)
            .fetch()
    }

    /**
     * Step 2 — Step 1 의 candidate ID 들로 per-RT aggregation (QueryDSL). 각 row 는 (propertyId, roomTypeId,
     * totalPrice). `HAVING COUNT(*) = nights` 로 *모든 일자 가용* RoomType 만 통과.
     *
     * 결과를 인메모리에서 propertyId 기준 GROUP BY → `PropertyAgg` Map.
     */
    private fun aggregatePerProperty(
        candidateIds: List<Long>,
        period: StayPeriod,
        guestCount: Int,
    ): Map<Long, PropertyAgg> {
        val rt = QRoomTypeModel.roomTypeModel
        val rate = QDailyRoomRateModel.dailyRoomRateModel
        val inv = QDailyRoomInventoryModel.dailyRoomInventoryModel
        val nights = period.nights().toLong()

        val tuples = queryFactory
            .select(rt.propertyId, rt.id, rate.pricePerNight.amount.sum())
            .from(rt)
            .innerJoin(rate).on(rate.roomTypeId.eq(rt.id))
            .innerJoin(inv).on(inv.roomTypeId.eq(rt.id).and(inv.date.eq(rate.date)))
            .where(
                rt.propertyId.`in`(candidateIds),
                rate.date.goe(period.checkIn),
                rate.date.lt(period.checkOut),
                rt.guestCount.max.goe(guestCount),
                inv.totalRooms.gt(inv.reservedRooms),
            )
            .groupBy(rt.propertyId, rt.id)
            .having(Expressions.numberTemplate(Long::class.java, "count(*)").eq(nights))
            .fetch()

        return tuples
            .groupBy { it.get(rt.propertyId) ?: 0L }
            .mapValues { (_, rows) ->
                PropertyAgg(
                    lowestTotal = rows.minOf { it.get(rate.pricePerNight.amount.sum()) ?: 0L },
                    availableRoomTypeCount = rows.size,
                )
            }
    }

    private fun toSearchRow(property: PropertyModel, agg: PropertyAgg): PropertySearchRow =
        PropertySearchRow(
            propertyId = property.id,
            name = property.name.value,
            city = property.address.city,
            fullAddress = property.address.fullAddress,
            mainImageUrl = property.mainImageUrl,
            starRating = property.starRating?.value,
            rating = property.rating.value,
            wishCount = property.wishCount,
            lowestTotalPrice = agg.lowestTotal,
            availableRoomTypeCount = agg.availableRoomTypeCount,
        )

    private data class PropertyAgg(val lowestTotal: Long, val availableRoomTypeCount: Int)

    override fun findAllByIds(ids: Collection<Long>): List<PropertyModel> =
        if (ids.isEmpty()) emptyList() else propertyJpaRepository.findAllById(ids).toList()

    override fun deleteById(id: Long) = propertyJpaRepository.deleteById(id)

    companion object {
        /**
         * PRICE_ASC 의 candidate overfetch 배수. page.size × 본 상수 만큼 SQL 에서 정렬 후 가져옴 — Facade 가
         * 후속 가용성 N+1 필터링 후 page.size 만큼 take. (week5-b.md decompose-decision Q4 박제)
         */
        private const val PRICE_ASC_CANDIDATE_MULTIPLIER: Int = 3

        /**
         * PR3 D-3 의 2-step batch IN 채택 후 *모든 sort* 의 candidate overfetch 배수. PRICE_ASC 외 sort 도
         * Step 2 의 inventory + HAVING 에서 일부 탈락 가능 → ×3 overfetch (`week5-b.md` decompose-decision δ).
         */
        private const val SEARCH_CANDIDATE_MULTIPLIER: Int = 3
    }

    /**
     * `wishCount` 1 증가 — QueryDSL `update().set(...).execute()`. (`docs/plan/week4/decision.md` D-6 정합)
     *
     * 운영 SQL: `UPDATE properties SET wish_count = wish_count + 1 WHERE id = ?` — read-modify-write 우회.
     * `@Modifying @Query` 대신 QueryDSL 채택 — 컴파일 시점 컬럼 오타 차단 + `@Query` 전면 제거 정책 정합.
     *
     * **persistence context staleness 주의**: 본 메서드는 entity manager 를 우회 — 같은 TX 안에서 미리 로드된
     * `PropertyModel` 의 `wishCount` 는 stale 상태로 남는다. WishlistFacade 흐름은 atomic 호출 후 *그
     * PropertyModel 을 다시 사용하지 않으므로* 안전 (응답의 wishCount 는 *본 호출의 +1 박제* — 동시 다른
     * thread 의 증감은 응답에 반영되지 않으나, DB 정합성은 atomic 으로 보장).
     */
    override fun atomicIncrementWishCount(propertyId: Long): Int {
        val p = QPropertyModel.propertyModel
        return queryFactory
            .update(p)
            .set(p.wishCount, p.wishCount.add(1))
            .where(p.id.eq(propertyId))
            .execute()
            .toInt()
    }

    /**
     * `wishCount` 1 감소 — *음수 진입 SQL 차단* `WHERE wish_count > 0`.
     *
     * 운영 SQL: `UPDATE properties SET wish_count = wish_count - 1 WHERE id = ? AND wish_count > 0`.
     * `wish_count = 0` 인 row 는 affected = 0 (멱등 noop) — 미찜 상태에 unwish 가 잘못 호출되어도 DB 가
     * 영구히 어긋나지 않는다 (decision.md D-1 #4).
     */
    override fun atomicDecrementWishCount(propertyId: Long): Int {
        val p = QPropertyModel.propertyModel
        return queryFactory
            .update(p)
            .set(p.wishCount, p.wishCount.subtract(1))
            .where(p.id.eq(propertyId).and(p.wishCount.gt(0)))
            .execute()
            .toInt()
    }
}
