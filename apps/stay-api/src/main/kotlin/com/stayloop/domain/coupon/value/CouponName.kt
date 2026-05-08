package com.stayloop.domain.coupon.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * 쿠폰 이름. (`docs/plan/week4.md` ① Phase A-2)
 *
 * - `CouponTemplate` 의 표시 이름
 * - 예약 시 `CouponSnapshot.couponName` 으로 박제 (② Reservation 합류 시점)
 *
 * 도메인 가드:
 * - 비공백
 * - `1 ~ MAX_NAME_LENGTH` 자
 */
@Embeddable
data class CouponName(
    @Column(name = "coupon_name", nullable = false, length = MAX_NAME_LENGTH)
    val value: String,
) {
    init {
        if (value.isBlank() || value.length > MAX_NAME_LENGTH) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "쿠폰 이름은 1~${MAX_NAME_LENGTH}자의 비공백 문자열이어야 합니다.",
            )
        }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_NAME_LENGTH: Int = 100
    }
}
