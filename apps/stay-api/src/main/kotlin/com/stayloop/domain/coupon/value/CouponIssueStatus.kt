package com.stayloop.domain.coupon.value

/**
 * 발급된 쿠폰 인스턴스의 상태 머신. (`docs/plan/week4.md` ① Phase A-4, `docs/plan/week4/decision.md` D-2)
 *
 * 3 상태 — `AVAILABLE / USED / EXPIRED`.
 *
 * **합법 전이 매트릭스 (3×3):**
 *
 * | from \ to     | AVAILABLE | USED | EXPIRED |
 * |---------------|:---------:|:----:|:-------:|
 * | AVAILABLE     |     -     |   ✓  |    ✓    |
 * | USED          |     -     |   -  |    -    |  (terminal)
 * | EXPIRED       |     -     |   -  |    -    |  (terminal)
 *
 * 의식적으로 빼는 전이:
 * - `USED → AVAILABLE` — 본 라운드는 쿠폰 *영구 소멸* 정책 (`docs/plan/week4/decision.md` D-4). 5~6주차
 *   결제 환불 정책 합류 시점에 *복원* 또는 *보상 발급* 으로 진화 — 본 enum 의 전이 규칙은 그때 재검토.
 * - `EXPIRED → AVAILABLE` — 만료된 쿠폰의 재활성화는 정책상 어색 (어드민이 새 템플릿 발급으로 처리).
 * - terminal 상태 (USED / EXPIRED) 에서의 어떤 전이도 불가 — 잘못된 재처리 방지.
 */
enum class CouponIssueStatus {
    AVAILABLE,
    USED,
    EXPIRED,
    ;

    fun canTransitTo(next: CouponIssueStatus): Boolean = next in allowedNext()

    private fun allowedNext(): Set<CouponIssueStatus> = when (this) {
        AVAILABLE -> setOf(USED, EXPIRED)
        USED, EXPIRED -> emptySet()
    }
}
