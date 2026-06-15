package com.stayloop.infrastructure.coupon

import com.querydsl.jpa.impl.JPAQueryFactory
import com.stayloop.domain.coupon.CouponTemplateModel
import com.stayloop.domain.coupon.CouponTemplateRepository
import com.stayloop.domain.coupon.QCouponTemplateModel
import org.springframework.stereotype.Component

@Component
class CouponTemplateRepositoryImpl(
    private val jpaRepository: CouponTemplateJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : CouponTemplateRepository {
    private val template = QCouponTemplateModel.couponTemplateModel

    override fun findById(id: Long): CouponTemplateModel? = jpaRepository.findById(id).orElse(null)

    override fun save(template: CouponTemplateModel): CouponTemplateModel = jpaRepository.save(template)

    override fun increaseIssuedIfAvailable(templateId: Long): Int =
        queryFactory
            .update(template)
            .set(template.issuedCount, template.issuedCount.add(1))
            .where(
                template.id.eq(templateId),
                template.issuedCount.add(1).loe(template.totalQuantity),
            )
            .execute()
            .toInt()
}
