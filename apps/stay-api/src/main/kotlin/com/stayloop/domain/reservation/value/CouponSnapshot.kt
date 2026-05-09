package com.stayloop.domain.reservation.value

import com.stayloop.domain.coupon.value.DiscountType
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated

/**
 * 예약 시점의 쿠폰 박제. (`docs/plan/week4.md` ② Commit 1, `docs/design/05 §2.5`)
 *
 * **박제 의도** — 예약 후 `CouponTemplate` 의 정책이 변경(이름/할인값/만료 수정)되거나 삭제되어도 *예약 당시의
 * 쿠폰 정보* 가 영수증·취소·CS 흐름에서 보존되어야 한다. `PropertySnapshot` / `RoomTypeSnapshot` 박제 패턴 답습.
 *
 * 박제 항목 (단순 primitives — 박제 컬럼이 폭발하지 않게 *직렬화된 의미값* 만):
 * - `couponId` — 발급 인스턴스(`CouponIssue`) FK 참조용 + CS 식별
 * - `couponName` — `CouponName.value` 박제
 * - `couponCode` — `CouponTemplate.code` 박제 (사용자가 *어떤 코드를 사용했는지* 영수증 표현)
 * - `discountType` — FIXED / RATE
 *
 * 도메인 가드:
 * - `couponId > 0` (영속화된 CouponIssue 참조)
 * - `couponName` 비공백, 1~`MAX_NAME_LENGTH(100)` 자
 * - `couponCode` 비공백, 1~`MAX_CODE_LENGTH(50)` 자
 *
 * **컬럼 length ↔ init 가드** 는 동일 const (verify-code §6 — 문서 ↔ 가드 정합).
 */
@Embeddable
data class CouponSnapshot(
    @Column(name = "coupon_id", nullable = false)
    val couponId: Long,
    @Column(name = "coupon_name", nullable = false, length = MAX_NAME_LENGTH)
    val couponName: String,
    @Column(name = "coupon_code", nullable = false, length = MAX_CODE_LENGTH)
    val couponCode: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "coupon_discount_type", nullable = false, length = 16)
    val discountType: DiscountType,
) {
    init {
        if (couponId <= 0L) {
            throw CoreException(ErrorType.BAD_REQUEST, "couponId 는 양수여야 합니다 (영속화된 CouponIssue 의 id).")
        }
        if (couponName.isBlank() || couponName.length > MAX_NAME_LENGTH) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "박제 couponName 은 1~${MAX_NAME_LENGTH}자의 비공백 문자열이어야 합니다.",
            )
        }
        if (couponCode.isBlank() || couponCode.length > MAX_CODE_LENGTH) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "박제 couponCode 는 1~${MAX_CODE_LENGTH}자의 비공백 문자열이어야 합니다.",
            )
        }
    }

    companion object {
        const val MAX_NAME_LENGTH: Int = 100
        const val MAX_CODE_LENGTH: Int = 50
    }
}
