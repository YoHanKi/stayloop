package com.stayloop.domain.reservation

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Service

/**
 * 기간 전체 요금을 합산하는 도메인 서비스. 여러 [DailyRoomRateModel] 의 협력이고 상태가 없어
 * 엔티티가 아니라 도메인 서비스에 둔다(03 §6). Repository 의존이 없어 순수 함수로 단위 테스트된다.
 *
 * 쿠폰·세금은 6주차 영역이라 시그니처에 선반영하지 않는다 — 합류 시 `Money.times` 자리에 추가한다(05 §8.2).
 */
@Service
class ReservationPriceCalculator {
    /** 1박씩의 요금을 모두 더한 총액. 요금 목록이 비어 있으면 BAD_REQUEST. */
    fun totalPrice(rates: List<DailyRoomRateModel>): Money {
        if (rates.isEmpty()) {
            throw CoreException(ErrorType.BAD_REQUEST, "합산할 요금 정보가 없습니다.")
        }
        return rates.fold(Money.ZERO) { acc, rate -> acc + rate.pricePerNight }
    }
}
