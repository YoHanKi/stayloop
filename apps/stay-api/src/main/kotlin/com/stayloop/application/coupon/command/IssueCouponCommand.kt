package com.stayloop.application.coupon.command

import com.stayloop.domain.user.value.LoginId

/**
 * 쿠폰 발급 Command. (`docs/plan/week4.md` ① Phase B-1)
 *
 * 사용자 (`actor: LoginId`) 가 *공개된 템플릿* (`templateId`) 을 발급받는 단순한 형태.
 * 6주차+ 합류 시점의 *코드 직접 입력 발급* (`POST /coupons/code/{code}/issue`) 또는
 * *선착순 한정* (max_redemptions) 이 도입되면 본 Command 가 분기될 자리.
 */
data class IssueCouponCommand(
    val actor: LoginId,
    val templateId: Long,
)
