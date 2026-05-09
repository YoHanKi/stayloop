package com.stayloop.application.coupon.admin

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.coupon.value.DiscountType
import java.time.LocalDateTime

/**
 * 어드민 — 쿠폰 템플릿 등록 Command. (`docs/plan/week4.md` ① Phase C-1)
 *
 * 본 Command 자체는 *raw 입력* 의 형태 — 도메인 VO 조립은 Facade 가 책임.
 * 도메인 VO 인스턴스화에 따른 가드 (예: `CouponName` 의 길이 / `DiscountValue` 의 범위) 가 자연스럽게 발동된다.
 *
 * **`minOrderAmount` 의 nullable 의미** — null 이면 *제한 없음*. `MinOrderAmount` VO 자체는 양수만 표현하므로
 * Facade 가 null 체크 후 VO 를 만든다.
 */
data class RegisterCouponTemplateCommand(
    val code: String,
    val name: String,
    val discountType: DiscountType,
    val discountValue: Long,
    val minOrderAmount: Money?,
    val expiredAt: LocalDateTime,
)

/**
 * 어드민 — 쿠폰 템플릿 수정 Command. (`docs/plan/week4.md` ① Phase C-1)
 *
 * 본 라운드는 *전체 필드 갱신* (PUT 의미론) — 부분 갱신 (PATCH) 은 본 라운드 미지원.
 * `code` 는 갱신 가능 (UNIQUE 제약은 동일 row 의 자기 자신만 다른 row 와 충돌하지 않으면 OK).
 */
data class UpdateCouponTemplateCommand(
    val templateId: Long,
    val code: String,
    val name: String,
    val discountType: DiscountType,
    val discountValue: Long,
    val minOrderAmount: Money?,
    val expiredAt: LocalDateTime,
)
