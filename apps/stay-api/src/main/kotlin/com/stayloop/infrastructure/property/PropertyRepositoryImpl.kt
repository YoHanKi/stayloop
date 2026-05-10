package com.stayloop.infrastructure.property

import com.querydsl.core.types.OrderSpecifier
import com.querydsl.core.types.dsl.ComparableExpressionBase
import com.querydsl.jpa.impl.JPAQueryFactory
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.common.value.PageResult
import com.stayloop.domain.common.value.SortDirection
import com.stayloop.domain.common.value.SortKey
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.PropertyRepository
import com.stayloop.domain.property.QPropertyModel
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Component

@Component
class PropertyRepositoryImpl(
    private val propertyJpaRepository: PropertyJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : PropertyRepository {
    override fun save(property: PropertyModel): PropertyModel = propertyJpaRepository.save(property)

    override fun findById(id: Long): PropertyModel? = propertyJpaRepository.findById(id).orElse(null)

    /**
     * 도시 기준 페이지 조회. **QueryDSL 직접 작성** — 문자열 JPQL `@Query` 가 아닌 type-safe 경로
     * (`p.address.city`) 로 표현하여 컴파일 시점에 컬럼 오타 / 경로 변경 회귀를 차단한다 (verify-code §17 / §19-B).
     *
     * 정렬 화이트리스트: `wishCount` / `rating` / `name`. 미등록 키는 `BAD_REQUEST` 로 거절 — 외부 입력의 임의
     * 키가 500 으로 터지는 것 방지 (Copilot #8).
     */
    override fun findByCity(city: String, query: PageQuery): PageResult<PropertyModel> {
        val p = QPropertyModel.propertyModel
        val condition = p.address.city.eq(city)

        val content = queryFactory
            .selectFrom(p)
            .where(condition)
            .orderBy(*toOrderSpecifiers(query.sort, p))
            .offset((query.page.toLong()) * query.size.toLong())
            .limit(query.size.toLong())
            .fetch()

        val total = queryFactory
            .select(p.count())
            .from(p)
            .where(condition)
            .fetchOne() ?: 0L

        return PageResult(content = content, total = total)
    }

    override fun findAllByIds(ids: Collection<Long>): List<PropertyModel> =
        if (ids.isEmpty()) emptyList() else propertyJpaRepository.findAllById(ids).toList()

    override fun deleteById(id: Long) = propertyJpaRepository.deleteById(id)

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

    /**
     * 도메인 정렬 어휘 → QueryDSL `OrderSpecifier` 변환. 화이트리스트 미등록 키는 BAD_REQUEST.
     * `@Embedded` VO 는 *내부 path* 로 명시 (Rating → `rating.value`, Name → `name.value`).
     */
    private fun toOrderSpecifiers(keys: List<SortKey>, p: QPropertyModel): Array<OrderSpecifier<*>> {
        if (keys.isEmpty()) return emptyArray()
        return keys
            .map { key ->
                val expr: ComparableExpressionBase<*> = when (key.property) {
                    "wishCount" -> p.wishCount
                    "rating" -> p.rating.value
                    "name" -> p.name.value
                    else -> throw CoreException(
                        ErrorType.BAD_REQUEST,
                        "지원하지 않는 정렬 키입니다: ${key.property}",
                    )
                }
                when (key.direction) {
                    SortDirection.ASC -> expr.asc()
                    SortDirection.DESC -> expr.desc()
                }
            }
            .toTypedArray()
    }
}
