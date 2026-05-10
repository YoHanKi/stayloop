package com.stayloop.domain.coupon.value

import com.stayloop.domain.common.value.Money
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType

/**
 * 할인 결과 박제 (스냅샷). (`docs/plan/week4/decision.md` D-3, `docs/plan/week4.md` ① Phase A-1)
 *
 * 쿠폰 정책 (`CouponTemplate`) 이 사후 변경되어도 *예약의 할인 표현* 은 흔들리지 않도록 적용 결과를 값 객체로 박제한다.
 * `RoomTypeSnapshot` / `PropertySnapshot` 의 박제 패턴 답습.
 *
 * 박제 항목:
 * - `beforeDiscount` — 할인 *전* 결제 금액 (일자별 요금 합산 결과).
 * - `amount` — 실제 차감된 할인 금액 (정액 = 그대로, 정률 = 계산 결과).
 * - `finalPrice` — 최종 결제 금액 (`beforeDiscount - amount`).
 *
 * 도메인 가드:
 * - `amount <= beforeDiscount` — 할인이 결제 금액을 초과할 수 없음
 *   (할인 후 음수 결제 = *환급* 형태 차단; 쿠폰은 *할인* 이지 *현금 환급* 이 아니다).
 * - `finalPrice == beforeDiscount - amount` — 산술 정합성. 외부에서 잘못 조립된 객체를 거부해
 *   "결제는 finalPrice, 환불은 amount" 같은 두 시스템이 서로 다른 진실을 읽는 회계 사고를 막는다.
 * - `finalPrice >= ZERO` — `Money` init 가드가 자동 보장. 박제 의도 명시 차원의 defense in depth.
 *
 * `finalPrice == 0` 은 *허용* — 정확히 일치하는 정액 쿠폰 / 완전 무료 promo 캠페인이 정합한 비즈니스 시나리오.
 */
data class Discount(
    val beforeDiscount: Money,
    val amount: Money,
    val finalPrice: Money,
) {
    init {
        if (amount > beforeDiscount) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "할인 금액은 결제 금액을 초과할 수 없습니다.",
            )
        }
        if (finalPrice != beforeDiscount - amount) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "할인 산술이 일치하지 않습니다 (finalPrice 가 beforeDiscount - amount 와 다름).",
            )
        }
        if (finalPrice < Money.ZERO) {
            throw CoreException(ErrorType.BAD_REQUEST, "최종 결제 금액은 음수가 될 수 없습니다.")
        }
    }

    companion object {
        /**
         * 쿠폰 미적용 케이스. `amount = ZERO`, `finalPrice = beforeDiscount`.
         *
         * `ReservationFacade.reserve` 가 `couponId == null` 일 때 박제값으로 사용한다 (② 합류 시점).
         */
        fun none(beforeDiscount: Money): Discount =
            Discount(
                beforeDiscount = beforeDiscount,
                amount = Money.ZERO,
                finalPrice = beforeDiscount,
            )
    }
}
