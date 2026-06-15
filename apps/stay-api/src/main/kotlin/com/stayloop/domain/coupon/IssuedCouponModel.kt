package com.stayloop.domain.coupon

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.coupon.value.CouponStatus
import com.stayloop.domain.coupon.value.DiscountValue
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

/**
 * 사용자가 발급받은 쿠폰. 발급 시점 할인 정책을 스냅샷해 이후 템플릿 변경의 영향을 끊는다.
 * `(couponTemplateId, userId)` UNIQUE 로 동일인 중복 발급을 막는다(04-b §2).
 *
 * 사용은 `AVAILABLE → USED` 단방향 전이다. 동일 쿠폰 동시 사용은 본인 한정의 저경합이라 비관 락 없이
 * 조건부 상태 전이([IssuedCouponRepository.markUsedIfAvailable])로 1 회성을 보장한다(04-a §6.3·§8).
 */
@Entity
@Table(
    name = "issued_coupons",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_issued_coupons_template_user", columnNames = ["coupon_template_id", "user_login_id"]),
    ],
)
class IssuedCouponModel(
    couponTemplateId: Long,
    userId: LoginId,
    discount: DiscountValue,
    status: CouponStatus = CouponStatus.AVAILABLE,
    usedAt: LocalDateTime? = null,
) : BaseEntity() {
    @Column(name = "coupon_template_id", nullable = false)
    val couponTemplateId: Long = couponTemplateId

    @Embedded
    @AttributeOverride(name = "value", column = Column(name = "user_login_id", nullable = false, length = 20))
    val userId: LoginId = userId

    @Embedded
    val discount: DiscountValue = discount

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: CouponStatus = status
        protected set

    @Column(name = "used_at")
    var usedAt: LocalDateTime? = usedAt
        protected set

    init {
        if (couponTemplateId <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "쿠폰 템플릿 ID 는 양수여야 합니다.")
        }
    }

    /** AVAILABLE → USED 전이(도메인 규칙). 운영 동시성 경로는 같은 전이를 조건부 UPDATE 로 원자 수행한다. */
    fun use(now: LocalDateTime) {
        if (status != CouponStatus.AVAILABLE) {
            throw CoreException(ErrorType.CONFLICT, "이미 사용했거나 사용할 수 없는 쿠폰입니다.")
        }
        status = CouponStatus.USED
        usedAt = now
    }

    /** USED → AVAILABLE 복원(예약 취소 시). 이미 AVAILABLE 이면 멱등하게 무시한다. */
    fun revertUse() {
        if (status == CouponStatus.USED) {
            status = CouponStatus.AVAILABLE
            usedAt = null
        }
    }
}
