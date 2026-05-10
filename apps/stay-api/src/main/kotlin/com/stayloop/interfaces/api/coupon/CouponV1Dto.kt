package com.stayloop.interfaces.api.coupon

import com.stayloop.application.coupon.CouponIssueInfo
import com.stayloop.domain.coupon.value.CouponIssueStatus
import com.stayloop.domain.coupon.value.DiscountType
import java.time.LocalDateTime

/**
 * 대고객 Coupon V1 DTO. (`docs/plan/week4.md` ① Phase B-3)
 *
 * `Money.amount` 는 `Long` (원 단위) — `BigDecimal` 노출은 통화 정밀도 보존이 본질일 때만 사용. 본 라운드는 KRW 정수
 * 원 단위라 `Long` 직렬화로 충분 (Reservation V1Dto 의 `totalPrice` 패턴 답습).
 */
class CouponV1Dto {
    /**
     * 발급 응답 / 단건 응답에 공통으로 사용.
     */
    data class CouponIssueResponse(
        val issueId: Long,
        val templateId: Long,
        val code: String,
        val name: String,
        val discountType: DiscountType,
        val discountValue: Long,
        val minOrderAmount: Long?,
        val expiredAt: LocalDateTime,
        val status: CouponIssueStatus,
        val issuedAt: LocalDateTime,
        val usedAt: LocalDateTime?,
    ) {
        companion object {
            fun from(info: CouponIssueInfo): CouponIssueResponse = CouponIssueResponse(
                issueId = info.issueId,
                templateId = info.templateId,
                code = info.code,
                name = info.name,
                discountType = info.discountType,
                discountValue = info.discountValue,
                minOrderAmount = info.minOrderAmount?.amount,
                expiredAt = info.expiredAt,
                status = info.status,
                issuedAt = info.issuedAt,
                usedAt = info.usedAt,
            )
        }
    }
}
