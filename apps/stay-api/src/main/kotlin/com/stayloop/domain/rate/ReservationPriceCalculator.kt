package com.stayloop.domain.rate

import com.stayloop.domain.common.value.Money
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Service

/**
 * 예약 기간의 일자별 요금 합산 도메인 서비스. (`docs/design/03-class-diagram.md §6`, `week3.md`)
 *
 * **시그니처는 `List<DailyRoomRateModel>` 단일 인자** — `Coupon?` / `Tax?` 등 부가 매개변수는
 * **본 라운드에 미리 추가하지 않는다** (`docs/plan/week2-3.md §⑤` 결정). 쿠폰/세금이 합류하는
 * 시점(6주차+)에 시그니처를 변경하거나 별도 `Discount` / `Tax` 객체를 도입한다 — 현재 미존재 매개변수의
 * 자리만 잡지 않는다 (YAGNI).
 *
 * 도메인 가드:
 * - `rates.isEmpty()` 면 BAD_REQUEST — 0박 예약은 도메인이 거절 (`StayPeriod` 가 1박 이상 보장하지만
 *   합산 단계에서 한 번 더 명시).
 * - 음수 가격 자체는 `Money.init` 에서 이미 차단됨 — 합산 단계까지 도달하지 않으므로 여기서 중복 검증 X.
 *
 * 본 라운드는 `Money.plus` 만 사용 — `times(N박)` 은 *동일 가격 × 박수* 시나리오 자리이지만
 * 본 도메인은 일자별 요금이 다를 수 있다는 전제이므로 `fold` 합산이 의미적으로 정확하다.
 */
@Service
class ReservationPriceCalculator {
    /**
     * 일자별 요금 목록의 합산. 결과는 항상 `Money` (음수 불가, Money.init 가드).
     *
     * @throws CoreException(BAD_REQUEST) `rates.isEmpty()` 일 때
     */
    fun totalPrice(rates: List<DailyRoomRateModel>): Money {
        if (rates.isEmpty()) {
            throw CoreException(ErrorType.BAD_REQUEST, "요금 목록이 비어 있어 합산할 수 없습니다.")
        }
        return rates.fold(Money.ZERO) { acc, rate -> acc + rate.pricePerNight }
    }
}
