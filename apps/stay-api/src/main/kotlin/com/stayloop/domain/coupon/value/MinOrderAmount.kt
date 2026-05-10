package com.stayloop.domain.coupon.value

import com.stayloop.domain.common.value.Money
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.Embedded

/**
 * 쿠폰 적용 최소 결제 금액. (`docs/plan/week4.md` ① Phase A-2)
 *
 * 정책 예시: "1만원 이상 결제 시에만 적용 가능".
 *
 * `CouponTemplate` 가 `MinOrderAmount?` 로 들고 있어, **null 이면 제한 없음** 의미. 본 VO 자체는
 * non-null 양수 금액을 표현한다 (nullable 처리는 부모에서).
 *
 * 도메인 가드:
 * - `value > 0` — 0 또는 음수면 의미 없음 (null 로 두는 게 맞음)
 *
 * 도메인 행동:
 * - [requireApplicable] — 결제 금액이 본 최소값 미만이면 BAD_REQUEST 거절
 */
@Embeddable
data class MinOrderAmount(
    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "min_order_amount", nullable = true))
    val value: Money,
) {
    init {
        if (value.isZero()) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "최소 결제 금액은 양수여야 합니다 (제한 없음은 null 로 표현).",
            )
        }
    }

    fun requireApplicable(orderAmount: Money) {
        if (orderAmount < value) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "최소 결제 금액 ($value) 미만이라 쿠폰을 적용할 수 없습니다.",
            )
        }
    }
}
