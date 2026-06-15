package com.stayloop.infrastructure.coupon

import com.querydsl.jpa.impl.JPAQueryFactory
import com.stayloop.domain.coupon.IssuedCouponModel
import com.stayloop.domain.coupon.IssuedCouponRepository
import com.stayloop.domain.coupon.QIssuedCouponModel
import com.stayloop.domain.coupon.value.CouponStatus
import com.stayloop.domain.user.value.LoginId
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class IssuedCouponRepositoryImpl(
    private val jpaRepository: IssuedCouponJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : IssuedCouponRepository {
    private val issued = QIssuedCouponModel.issuedCouponModel

    override fun findById(id: Long): IssuedCouponModel? = jpaRepository.findById(id).orElse(null)

    override fun save(issued: IssuedCouponModel): IssuedCouponModel = jpaRepository.save(issued)

    override fun findByUser(userId: LoginId, page: Int, size: Int): List<IssuedCouponModel> =
        queryFactory
            .selectFrom(issued)
            .where(issued.userId.value.eq(userId.value))
            .orderBy(issued.createdAt.desc(), issued.id.desc())
            .offset(page.toLong() * size)
            .limit(size.toLong())
            .fetch()

    override fun markUsedIfAvailable(issuedCouponId: Long, usedAt: LocalDateTime): Int =
        queryFactory
            .update(issued)
            .set(issued.status, CouponStatus.USED)
            .set(issued.usedAt, usedAt)
            .where(
                issued.id.eq(issuedCouponId),
                issued.status.eq(CouponStatus.AVAILABLE),
            )
            .execute()
            .toInt()
}
