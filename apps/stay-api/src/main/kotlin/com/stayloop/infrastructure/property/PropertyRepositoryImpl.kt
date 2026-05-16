package com.stayloop.infrastructure.property

import com.querydsl.core.types.OrderSpecifier
import com.querydsl.jpa.impl.JPAQueryFactory
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.common.value.PageResult
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.PropertyRepository
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

    override fun findAllByIds(ids: Collection<Long>): List<PropertyModel> =
        if (ids.isEmpty()) emptyList() else propertyJpaRepository.findAllById(ids).toList()

    override fun deleteById(id: Long) = propertyJpaRepository.deleteById(id)

    companion object {
        /**
         * PRICE_ASC 의 candidate overfetch 배수. page.size × 본 상수 만큼 SQL 에서 정렬 후 가져옴 — Facade 가
         * 후속 가용성 N+1 필터링 후 page.size 만큼 take. (week5-b.md decompose-decision Q4 박제)
         */
        private const val PRICE_ASC_CANDIDATE_MULTIPLIER: Int = 3
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
