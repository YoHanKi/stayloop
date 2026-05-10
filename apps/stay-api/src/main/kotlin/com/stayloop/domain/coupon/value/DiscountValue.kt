package com.stayloop.domain.coupon.value

import com.stayloop.domain.common.value.Money
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.EnumType
import jakarta.persistence.Embeddable
import jakarta.persistence.Enumerated

/**
 * 쿠폰 할인 정책. (`docs/plan/week4.md` ① Phase A-2)
 *
 * 정액(`FIXED`) 과 정률(`RATE`) 을 *동일 클래스 + type 분기* 로 표현한다 (sealed interface 가 아닌 이유:
 * 기존 도메인이 sealed 패턴을 쓰지 않아 영속화 매핑 / 패턴 정합성 모두 단일 클래스 쪽이 자연스러움).
 *
 * 의미:
 * - `FIXED` — `rawValue` 가 원 단위 정액 할인 금액 (`> 0`).
 * - `RATE` — `rawValue` 가 % (`1 ~ 100`).
 *
 * 도메인 가드:
 * - `FIXED` 이면 `rawValue > 0` — 0원 정액 쿠폰은 등록 자체를 거절. (이전 라운드는 `>= 0` 이었으나
 *   `ReservationModel` 의 *할인 금액 0 ↔ 쿠폰 박제 null* 불변식 (`CouponSnapshot`) 과 충돌 → 0원 FIXED 가
 *   등록은 통과하지만 *예약 적용 시점* 에 `BAD_REQUEST` 로 실패하는 *지연 폭발* 패턴. 도메인 일관성을
 *   위해 *발급 시점* 에서 거절. verify-code §6 "비즈니스 의미 0 가드" 정합.)
 * - `RATE` 이면 `1 <= rawValue <= 100` (0% 또는 음수는 의미 없음, 100% 초과는 환급 형태)
 *
 * 도메인 행동:
 * - [apply] — 결제 금액에 할인을 적용해 *차감 금액* (Money) 을 반환.
 *   `FIXED` 가 결제 금액을 초과하면 `beforeDiscount` 만큼만 cap (전액 할인 → 0원 결제 = `Discount` 가드와 정합).
 *   실제 cap 발동을 막는 *비즈니스 가드* 는 `MinOrderAmount.requireApplicable` 이 별도로 책임진다.
 */
@Embeddable
data class DiscountValue(
    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 16)
    val type: DiscountType,
    @Column(name = "discount_raw_value", nullable = false)
    val rawValue: Long,
) {
    init {
        when (type) {
            DiscountType.FIXED ->
                if (rawValue <= 0L) {
                    throw CoreException(
                        ErrorType.BAD_REQUEST,
                        "정액 할인 금액은 1원 이상이어야 합니다.",
                    )
                }
            DiscountType.RATE ->
                if (rawValue !in 1L..100L) {
                    throw CoreException(
                        ErrorType.BAD_REQUEST,
                        "정률 할인은 1 ~ 100 사이여야 합니다.",
                    )
                }
        }
    }

    fun apply(beforeDiscount: Money): Money {
        val raw =
            when (type) {
                DiscountType.FIXED -> Money.of(rawValue)
                DiscountType.RATE -> Money.of(beforeDiscount.amount * rawValue / 100)
            }
        return if (raw > beforeDiscount) beforeDiscount else raw
    }
}
