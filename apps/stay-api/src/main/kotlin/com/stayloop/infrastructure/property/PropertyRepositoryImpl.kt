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
     * sort 4종 활성 (D-6). RECOMMENDED / WISHES_DESC / RATING_DESC 는 properties 단일 테이블 인덱스 prefix scan,
     * PRICE_ASC 는 JOIN + GROUP BY MIN — 별도 hybrid 흐름.
     * total 은 city 매칭 row 수 (AC-1: 가용성은 응답 시점 상태, total 의미 비분리).
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
     * PRICE_ASC hybrid — SQL JOIN GROUP BY MIN 으로 ID 정렬 후 `findAllById` 로 hydrate (2 round-trip).
     * MySQL ONLY_FULL_GROUP_BY 회피 + GROUP BY 비용 분리 목적. K = page.size × 3 overfetch — Facade 가
     * 후속 가용성 필터 후 page.size take.
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
            .orderBy(rate.pricePerNight.amount.min().asc())
            .offset(page.offset.toLong())
            .limit((page.size * PRICE_ASC_CANDIDATE_MULTIPLIER).toLong())
            .fetch()

        if (sortedIds.isEmpty()) return emptyList()

        val byId = propertyJpaRepository.findAllById(sortedIds).associateBy { it.id }
        // findAllById 는 PK 순으로 반환할 수 있어 정렬 순서 강제
        return sortedIds.mapNotNull { byId[it] }
    }

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
     * N+1 제거 검색 (D-3, 2-step batch IN, 4 SQL).
     *
     * 1. Step 1 — candidate IDs (K = page.size × 3) 추출, sort 별 인덱스 prefix scan.
     * 2. Step 2 — candidate IDs 의 per-RT aggregation + HAVING COUNT(*) = nights 로 *모든 일자 가용* 만 통과.
     * 3. Step 2.5 — 인메모리 groupBy propertyId, MIN(total) + COUNT(rt).
     * 4. Step 3 — eligible Property entity fetch (sort 순서 보존, PRICE_ASC 시 lowest_total 재정렬).
     *
     * 초기 가설 (1쿼리 projection) 은 LIMIT 20 인데 inner 가 city 전체 집계로 *낭비* — GR-3 절차 후 2-step
     * 으로 재채택.
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

        // sort 보존 — PRICE_ASC 는 Step 2 의 lowest_total 재정렬, 그 외는 Step 1 순서
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

    /** Step 1 — candidate ID 추출. rate 존재 + max_guests 사전 필터로 Step 2 비용 축소. */
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

    /** Step 2 — candidate ID 들로 per-RT aggregation. HAVING COUNT = nights 로 모든 일자 가용만 통과. */
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
        private const val PRICE_ASC_CANDIDATE_MULTIPLIER: Int = 3
        private const val SEARCH_CANDIDATE_MULTIPLIER: Int = 3
    }

    /**
     * `wishCount += 1` atomic UPDATE. read-modify-write 우회 (D-6).
     *
     * persistence context staleness: 같은 TX 안 미리 로드된 PropertyModel.wishCount 는 stale 로 남음 —
     * 호출자는 응답에 *본 호출의 +1* 만 박제 (동시 다른 thread 증감은 응답 미반영, DB 정합성은 atomic 보장).
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
     * `wishCount -= 1` atomic UPDATE. `WHERE wish_count > 0` 으로 음수 진입 SQL 차단 — 미찜 unwish 가 잘못
     * 호출되어도 affected = 0 으로 멱등 noop (D-1 #4).
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
