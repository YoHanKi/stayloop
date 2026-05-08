package com.stayloop.domain.coupon.value

/**
 * 쿠폰 할인 타입.
 *
 * - [FIXED] 정액 할인 — 정해진 원 단위 금액을 결제 금액에서 차감.
 * - [RATE] 정률 할인 — 결제 금액의 N% 만큼 차감 (`0 < N <= 100`).
 *
 * 박제: `docs/plan/week4/decision.md` D-2 (Coupon 도메인 분리),
 * `docs/presentation/week4-quests.md` 어드민 등록 요청 예시.
 */
enum class DiscountType {
    FIXED,
    RATE,
}
