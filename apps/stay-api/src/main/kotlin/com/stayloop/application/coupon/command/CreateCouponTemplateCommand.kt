package com.stayloop.application.coupon.command

import com.stayloop.domain.coupon.value.DiscountType

data class CreateCouponTemplateCommand(
    val name: String,
    val discountType: DiscountType,
    val discountValue: Int,
    val totalQuantity: Int,
)
