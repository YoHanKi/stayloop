package com.stayloop.domain.coupon.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.time.LocalDateTime

/**
 * 쿠폰 유효 기간 (만료 시각). (`docs/plan/week4.md` ① Phase A-2)
 *
 * `CouponTemplate` 단위 글로벌 캠페인 마감 시각 — 발급된 모든 인스턴스가 동일 만료 시각을 따른다.
 * (예: "신규가입 1만원 할인 쿠폰은 2026-06-30 23:59:59 까지 유효").
 *
 * 발급일 기준 N일 만료 정책 (예: "발급 후 30일") 은 본 라운드 미적용 — 6주차+ 합류 시점.
 *
 * 도메인 행동:
 * - [isExpired] — `now >= expiredAt` 이면 만료. 조회 시점 lazy 판정에 사용 (`CouponFacade.getMyCoupons`).
 */
@Embeddable
data class ExpirationPeriod(
    @Column(name = "expired_at", nullable = false)
    val expiredAt: LocalDateTime,
) {
    fun isExpired(now: LocalDateTime): Boolean = !now.isBefore(expiredAt)

    fun requireUsable(now: LocalDateTime) {
        if (isExpired(now)) {
            throw CoreException(ErrorType.BAD_REQUEST, "만료된 쿠폰입니다.")
        }
    }
}
