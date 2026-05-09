package com.stayloop.application.coupon

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.coupon.CouponIssueModel
import com.stayloop.domain.coupon.CouponTemplateModel
import com.stayloop.domain.coupon.value.CouponIssueStatus
import com.stayloop.domain.coupon.value.DiscountType
import java.time.LocalDateTime

/**
 * 쿠폰 템플릿 (정책) 응답. (`docs/plan/week4.md` ① Phase B-1)
 *
 * 어드민 / 대고객 양쪽에서 공통으로 사용 — 정책 자체는 정보 노출 위험이 없다 (사용자가 어떤 쿠폰을 받았는지는
 * `CouponIssueInfo` 가 표현). 어드민 List/Detail 에서는 본 Info 만 사용.
 */
data class CouponTemplateInfo(
    val templateId: Long,
    val code: String,
    val name: String,
    val discountType: DiscountType,
    val discountValue: Long,
    val minOrderAmount: Money?,
    val expiredAt: LocalDateTime,
) {
    companion object {
        fun from(template: CouponTemplateModel): CouponTemplateInfo = CouponTemplateInfo(
            templateId = template.id,
            code = template.code,
            name = template.name.value,
            discountType = template.discountValue.type,
            discountValue = template.discountValue.rawValue,
            minOrderAmount = template.minOrderAmount?.value,
            expiredAt = template.expirationPeriod.expiredAt,
        )
    }
}

/**
 * 사용자가 발급받은 쿠폰 1건 응답. (`docs/plan/week4.md` ① Phase B-1)
 *
 * **`status` 는 *조회 시점 lazy 만료* 결과** — `CouponIssueModel.status == AVAILABLE` 이라도 현재 시각이
 * `template.expiredAt` 을 지났으면 `EXPIRED` 로 표현해 응답. 영속화는 안 함 (실제 모델 status 는 그대로 둔다)
 * — 만료 배치 트리거가 합류하기 전 (6주차+) 의 임시 정책.
 */
data class CouponIssueInfo(
    val issueId: Long,
    val templateId: Long,
    val code: String,
    val name: String,
    val discountType: DiscountType,
    val discountValue: Long,
    val minOrderAmount: Money?,
    val expiredAt: LocalDateTime,
    val status: CouponIssueStatus,
    val issuedAt: LocalDateTime,
    val usedAt: LocalDateTime?,
) {
    companion object {
        /**
         * `issue.status == AVAILABLE` 이지만 `template.expiredAt <= now` 이면 응답 status 를 `EXPIRED` 로 표현.
         * 영속 모델 자체는 변경하지 않는다 (lazy 표현 — `docs/plan/week4.md` ① Phase B-2).
         */
        fun of(
            issue: CouponIssueModel,
            template: CouponTemplateModel,
            now: LocalDateTime,
        ): CouponIssueInfo {
            val effectiveStatus =
                if (issue.status == CouponIssueStatus.AVAILABLE && template.isExpired(now)) {
                    CouponIssueStatus.EXPIRED
                } else {
                    issue.status
                }
            return CouponIssueInfo(
                issueId = issue.id,
                templateId = template.id,
                code = template.code,
                name = template.name.value,
                discountType = template.discountValue.type,
                discountValue = template.discountValue.rawValue,
                minOrderAmount = template.minOrderAmount?.value,
                expiredAt = template.expirationPeriod.expiredAt,
                status = effectiveStatus,
                issuedAt = issue.issuedAt,
                usedAt = issue.usedAt,
            )
        }
    }
}
