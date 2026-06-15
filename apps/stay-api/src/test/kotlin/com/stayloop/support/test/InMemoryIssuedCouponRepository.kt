package com.stayloop.support.test

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.coupon.IssuedCouponModel
import com.stayloop.domain.coupon.IssuedCouponRepository
import com.stayloop.domain.coupon.value.CouponStatus
import com.stayloop.domain.user.value.LoginId
import org.springframework.dao.DataIntegrityViolationException
import java.time.LocalDateTime

/**
 * 운영 어댑터와 동치 의미론의 더블. `(couponTemplateId, userId)` UNIQUE 를 흉내내 중복 저장 시
 * [DataIntegrityViolationException] 을 던지고(운영 제약 위반과 동치), `markUsedIfAvailable` 은
 * AVAILABLE 일 때만 USED 로 전이하고 1 을, 아니면 0 을 돌려준다. 정렬은 id 역순(발급 최신순 동치).
 */
class InMemoryIssuedCouponRepository : IssuedCouponRepository {
    private val store = LinkedHashMap<Long, IssuedCouponModel>()
    private var sequence = 0L

    override fun findById(id: Long): IssuedCouponModel? = store[id]

    override fun save(issued: IssuedCouponModel): IssuedCouponModel {
        val duplicate = store.values.any {
            it !== issued && it.couponTemplateId == issued.couponTemplateId && it.userId == issued.userId
        }
        if (duplicate) {
            throw DataIntegrityViolationException("이미 발급된 쿠폰입니다(uk_issued_coupons_template_user).")
        }
        if (issued.id == 0L) {
            assignId(issued, ++sequence)
        }
        store[issued.id] = issued
        return issued
    }

    override fun findByUser(userId: LoginId, page: Int, size: Int): List<IssuedCouponModel> =
        store.values
            .filter { it.userId == userId }
            .sortedByDescending { it.id }
            .drop(page * size)
            .take(size)

    override fun markUsedIfAvailable(issuedCouponId: Long, usedAt: LocalDateTime): Int {
        val issued = store[issuedCouponId] ?: return 0
        if (issued.status != CouponStatus.AVAILABLE) return 0
        issued.use(usedAt)
        return 1
    }

    private fun assignId(entity: BaseEntity, id: Long) {
        val field = BaseEntity::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.setLong(entity, id)
    }
}
