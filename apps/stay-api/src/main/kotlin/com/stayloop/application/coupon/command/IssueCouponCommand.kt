package com.stayloop.application.coupon.command

import com.stayloop.domain.user.value.LoginId

data class IssueCouponCommand(
    val loginId: LoginId,
    val templateId: Long,
)
