package com.stayloop.interfaces.api.coupon

import com.stayloop.application.coupon.CouponTemplateInfo
import com.stayloop.application.coupon.IssuedCouponInfo
import com.stayloop.application.coupon.command.CreateCouponTemplateCommand
import com.stayloop.domain.coupon.value.DiscountType

class CouponV1Dto {
    data class CreateTemplateRequest(
        val name: String,
        val discountType: DiscountType,
        val discountValue: Int,
        val totalQuantity: Int,
    ) {
        fun toCommand(): CreateCouponTemplateCommand =
            CreateCouponTemplateCommand(
                name = name,
                discountType = discountType,
                discountValue = discountValue,
                totalQuantity = totalQuantity,
            )
    }

    data class TemplateResponse(
        val templateId: Long,
        val name: String,
        val discountType: String,
        val discountValue: Int,
        val totalQuantity: Int,
        val issuedCount: Int,
        val remaining: Int,
    ) {
        companion object {
            fun from(info: CouponTemplateInfo): TemplateResponse =
                TemplateResponse(
                    templateId = info.templateId,
                    name = info.name,
                    discountType = info.discountType,
                    discountValue = info.discountValue,
                    totalQuantity = info.totalQuantity,
                    issuedCount = info.issuedCount,
                    remaining = info.remaining,
                )
        }
    }

    data class IssuedCouponResponse(
        val issuedCouponId: Long,
        val couponTemplateId: Long,
        val discountType: String,
        val discountValue: Int,
        val status: String,
        val usedAt: String?,
    ) {
        companion object {
            fun from(info: IssuedCouponInfo): IssuedCouponResponse =
                IssuedCouponResponse(
                    issuedCouponId = info.issuedCouponId,
                    couponTemplateId = info.couponTemplateId,
                    discountType = info.discountType,
                    discountValue = info.discountValue,
                    status = info.status,
                    usedAt = info.usedAt,
                )
        }
    }
}
