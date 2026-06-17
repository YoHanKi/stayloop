package com.stayloop.domain.coupon

import com.stayloop.domain.coupon.value.DiscountValue
import com.stayloop.domain.BaseEntity
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.Check

/**
 * 선착순 발급 쿠폰 캠페인. `issuedCount` 가 다수 사용자의 동시 발급이 다투는 핫스팟 카운터다(04-a §6.3·§8).
 *
 * 발급 수 증가는 엔티티 변이가 아니라 조건부 원자 UPDATE([CouponTemplateRepository.increaseIssuedIfAvailable])로
 * 수행한다 — 재고 차감과 동형. 음수·초과 발급은 DB CHECK 제약을 최후 방어선으로 둔다.
 */
@Entity
@Table(name = "coupon_templates")
@Check(constraints = "issued_count >= 0 AND issued_count <= total_quantity")
class CouponTemplateModel(
    name: String,
    discount: DiscountValue,
    totalQuantity: Int,
    issuedCount: Int = 0,
) : BaseEntity() {
    @Column(name = "name", nullable = false, length = 100)
    val name: String = name

    @Embedded
    val discount: DiscountValue = discount

    @Column(name = "total_quantity", nullable = false)
    var totalQuantity: Int = totalQuantity
        protected set

    @Column(name = "issued_count", nullable = false)
    var issuedCount: Int = issuedCount
        protected set

    init {
        if (name.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "쿠폰 이름은 비어 있을 수 없습니다.")
        }
        if (totalQuantity <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "한정 수량은 0보다 커야 합니다.")
        }
        if (issuedCount < 0 || issuedCount > totalQuantity) {
            throw CoreException(ErrorType.BAD_REQUEST, "발급 수는 0 이상 한정 수량 이하여야 합니다.")
        }
    }

    /** 남은 발급 가능 수량. */
    fun remaining(): Int = totalQuantity - issuedCount
}
