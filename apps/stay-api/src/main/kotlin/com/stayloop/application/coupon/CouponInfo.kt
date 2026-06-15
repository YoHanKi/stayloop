package com.stayloop.application.coupon

import com.stayloop.domain.coupon.CouponTemplateModel
import com.stayloop.domain.coupon.IssuedCouponModel

data class CouponTemplateInfo(
    val templateId: Long,
    val name: String,
    val discountType: String,
    val discountValue: Int,
    val totalQuantity: Int,
    val issuedCount: Int,
    val remaining: Int,
) {
    companion object {
        fun from(model: CouponTemplateModel): CouponTemplateInfo =
            CouponTemplateInfo(
                templateId = model.id,
                name = model.name,
                discountType = model.discount.type.name,
                discountValue = model.discount.amount,
                totalQuantity = model.totalQuantity,
                issuedCount = model.issuedCount,
                remaining = model.remaining(),
            )
    }
}

data class IssuedCouponInfo(
    val issuedCouponId: Long,
    val couponTemplateId: Long,
    val discountType: String,
    val discountValue: Int,
    val status: String,
    val usedAt: String?,
) {
    companion object {
        fun from(model: IssuedCouponModel): IssuedCouponInfo =
            IssuedCouponInfo(
                issuedCouponId = model.id,
                couponTemplateId = model.couponTemplateId,
                discountType = model.discount.type.name,
                discountValue = model.discount.amount,
                status = model.status.name,
                usedAt = model.usedAt?.toString(),
            )
    }
}
