package com.stayloop.infrastructure.coupon

import com.querydsl.jpa.impl.JPAQueryFactory
import com.stayloop.domain.coupon.CouponTemplateModel
import com.stayloop.domain.coupon.CouponTemplateRepository
import com.stayloop.domain.coupon.QCouponTemplateModel
import org.springframework.stereotype.Component

/**
 * 도메인 `CouponTemplateRepository` 의 인프라 어댑터. JpaRepository 위임 + QueryDSL.
 *
 * `findByCode` 는 type-safe QueryDSL 경로 — `coupon_templates.code` UNIQUE 제약이 1행 보장.
 */
@Component
class CouponTemplateRepositoryImpl(
    private val jpa: CouponTemplateJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : CouponTemplateRepository {

    override fun save(template: CouponTemplateModel): CouponTemplateModel = jpa.save(template)

    override fun findById(id: Long): CouponTemplateModel? = jpa.findById(id).orElse(null)

    override fun findByCode(code: String): CouponTemplateModel? {
        val t = QCouponTemplateModel.couponTemplateModel
        return queryFactory.selectFrom(t).where(t.code.eq(code)).fetchOne()
    }

    override fun findAllByIds(ids: Collection<Long>): List<CouponTemplateModel> {
        if (ids.isEmpty()) return emptyList()
        val t = QCouponTemplateModel.couponTemplateModel
        return queryFactory.selectFrom(t).where(t.id.`in`(ids)).fetch()
    }
}
