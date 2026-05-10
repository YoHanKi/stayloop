package com.stayloop.domain.rate

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.coupon.value.Discount
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Service

/**
 * 예약 기간의 일자별 요금 합산 도메인 서비스. (`docs/design/03-class-diagram.md §6`, `docs/plan/week4.md` ② Commit 2,
 * `docs/plan/week4/decision.md` D-3)
 *
 * **시그니처 = `(rates, discount: Discount?)`** — 4주차 ② 합류 시점에 `Discount?` 인자가 도입됐다.
 * Calculator 가 *Coupon 도메인 자체* 를 의존하지 않고 *이미 계산된 할인 결과 (Discount VO)* 만 받는다.
 * Coupon 검증 / 사용 처리는 Facade + `CouponIssueService` 책임 — Calculator 는 *순수 함수* 의 위치 (의도적으로 좁게).
 *
 * 의미:
 * - `discount == null` → 쿠폰 미적용. `rates.fold(plus)` 결과 그대로 반환.
 * - `discount != null` → Calculator 는 *`discount.beforeDiscount` 가 합산 결과와 일치* 하는지 검증한 뒤
 *   `discount.finalPrice` 를 반환. 일치하지 않으면 BAD_REQUEST (외부에서 잘못 조립된 Discount 거부).
 *
 * 도메인 가드:
 * - `rates.isEmpty()` 면 BAD_REQUEST — 0박 예약은 도메인이 거절 (`StayPeriod` 가 1박 이상 보장하지만
 *   합산 단계에서 한 번 더 명시).
 * - 음수 가격 자체는 `Money.init` 에서 이미 차단됨 — 합산 단계까지 도달하지 않으므로 여기서 중복 검증 X.
 * - **`discount != null` 일 때 `discount.beforeDiscount == rates 합산`** — 외부에서 잘못 조립된 Discount 가
 *   *다른 합산 기준* 으로 만들어졌다면 회계 정합 깨짐 (verify-code §17 — 인자 정합성).
 *
 * 본 라운드는 `Money.plus` 만 사용 — `times(N박)` 은 *동일 가격 × 박수* 시나리오 자리이지만
 * 본 도메인은 일자별 요금이 다를 수 있다는 전제이므로 `fold` 합산이 의미적으로 정확하다.
 */
@Service
class ReservationPriceCalculator {
    /**
     * 일자별 요금 합산 + 선택적 할인 적용. 결과는 항상 `Money` (음수 불가, Money.init 가드).
     *
     * @param rates    일자별 요금 목록 (빈 리스트 거절)
     * @param discount 쿠폰 적용 시 박제값. null 이면 합산 결과 그대로 반환
     * @throws CoreException(BAD_REQUEST) `rates.isEmpty()` 또는 discount.beforeDiscount 와 합산 불일치
     */
    fun totalPrice(rates: List<DailyRoomRateModel>, discount: Discount? = null): Money {
        if (rates.isEmpty()) {
            throw CoreException(ErrorType.BAD_REQUEST, "요금 목록이 비어 있어 합산할 수 없습니다.")
        }
        val sum = rates.fold(Money.ZERO) { acc, rate -> acc + rate.pricePerNight }
        if (discount == null) return sum
        if (discount.beforeDiscount != sum) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "할인 적용 기준 금액이 일자별 요금 합산과 일치하지 않습니다.",
            )
        }
        return discount.finalPrice
    }
}
