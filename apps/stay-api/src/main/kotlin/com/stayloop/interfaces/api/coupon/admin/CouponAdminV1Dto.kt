package com.stayloop.interfaces.api.coupon.admin

import com.stayloop.application.coupon.CouponTemplateInfo
import com.stayloop.application.coupon.admin.CouponAdminIssueInfo
import com.stayloop.application.coupon.admin.RegisterCouponTemplateCommand
import com.stayloop.application.coupon.admin.UpdateCouponTemplateCommand
import com.stayloop.domain.common.value.Money
import com.stayloop.domain.coupon.value.CouponIssueStatus
import com.stayloop.domain.coupon.value.DiscountType
import java.time.LocalDateTime

/**
 * 어드민 Coupon V1 DTO. (`docs/plan/week4.md` ① Phase C-2)
 *
 * **응답 평탄화** — 발급 이력 응답은 `CouponAdminIssueInfo` 의 컴포지션 구조를 *평탄화* 해 어드민 화면 친화적인
 * JSON 으로 직렬화 (V1Dto 의 책임 — 도메인 / Application 의 컴포지션 구조와 인터페이스 응답을 분리).
 */
class CouponAdminV1Dto {
    data class RegisterRequest(
        val code: String,
        val name: String,
        val type: DiscountType,
        val value: Long,
        val minOrderAmount: Long?,
        val expiredAt: LocalDateTime,
    ) {
        fun toCommand(): RegisterCouponTemplateCommand = RegisterCouponTemplateCommand(
            code = code,
            name = name,
            discountType = type,
            discountValue = value,
            minOrderAmount = minOrderAmount?.let { Money.of(it) },
            expiredAt = expiredAt,
        )
    }

    data class UpdateRequest(
        val code: String,
        val name: String,
        val type: DiscountType,
        val value: Long,
        val minOrderAmount: Long?,
        val expiredAt: LocalDateTime,
    ) {
        fun toCommand(templateId: Long): UpdateCouponTemplateCommand = UpdateCouponTemplateCommand(
            templateId = templateId,
            code = code,
            name = name,
            discountType = type,
            discountValue = value,
            minOrderAmount = minOrderAmount?.let { Money.of(it) },
            expiredAt = expiredAt,
        )
    }

    data class TemplateResponse(
        val templateId: Long,
        val code: String,
        val name: String,
        val type: DiscountType,
        val value: Long,
        val minOrderAmount: Long?,
        val expiredAt: LocalDateTime,
    ) {
        companion object {
            fun from(info: CouponTemplateInfo): TemplateResponse = TemplateResponse(
                templateId = info.templateId,
                code = info.code,
                name = info.name,
                type = info.discountType,
                value = info.discountValue,
                minOrderAmount = info.minOrderAmount?.amount,
                expiredAt = info.expiredAt,
            )
        }
    }

    /**
     * 어드민 발급 이력 응답 — 평탄화. `userLoginId` 는 사용자 hard delete + 잔존 시 `<unknown>` 으로 표현.
     */
    data class IssueResponse(
        val issueId: Long,
        val userLoginId: String,
        val templateId: Long,
        val code: String,
        val name: String,
        val status: CouponIssueStatus,
        val issuedAt: LocalDateTime,
        val usedAt: LocalDateTime?,
    ) {
        companion object {
            fun from(info: CouponAdminIssueInfo): IssueResponse = IssueResponse(
                issueId = info.issue.issueId,
                userLoginId = info.userLoginId,
                templateId = info.issue.templateId,
                code = info.issue.code,
                name = info.issue.name,
                status = info.issue.status,
                issuedAt = info.issue.issuedAt,
                usedAt = info.issue.usedAt,
            )
        }
    }
}
