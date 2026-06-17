package com.stayloop.domain.reservation

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.coupon.value.DiscountValue
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.reservation.value.GuestInfo
import com.stayloop.domain.reservation.value.PropertySnapshot
import com.stayloop.domain.reservation.value.RoomTypeSnapshot
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 예약 도메인 서비스. Repository 의존도 `@Transactional` 도 없다(03 §4) — 요금은 호출자(Facade)가
 * 미리 조회해 인자로 주입하고, 본 서비스는 받은 컬렉션으로 규칙만 수행한다. 트랜잭션 경계는 Facade.
 *
 * 재고 차감/복원은 동시성 제어(락·조건부 UPDATE)가 필요한 영속성 관심사라 [com.stayloop.domain.inventory.RoomInventoryReserver]
 * 가 임계 구간에서 담당한다(04-b §5-1). 본 서비스는 락 밖에서 도는 인원·요금 규칙과 예약 생성만 맡는다.
 */
@Service
class ReservationService(
    private val priceCalculator: ReservationPriceCalculator,
) {
    /**
     * 인원·요금 일자 정합을 검증하고 할인 전 합산 요금·할인액·최종액을 스냅샷해 PENDING 예약을 만든다.
     * 재고 차감·쿠폰 사용은 하지 않는다(호출자가 임계 구간에서 수행). 금액 스냅샷은 생성 시점에 고정돼
     * 이후 요금·쿠폰 정책 변경의 영향을 받지 않는다(04-b §2).
     *
     * @param discount 적용할 쿠폰 할인(없으면 null → 할인 0). @param couponId 사용한 발급 쿠폰 식별자(없으면 null).
     */
    fun reserve(
        userId: LoginId,
        property: PropertySnapshot,
        roomType: RoomTypeSnapshot,
        period: StayPeriod,
        guestCount: Int,
        guest: GuestInfo,
        rates: List<DailyRoomRateModel>,
        discount: DiscountValue?,
        couponId: Long?,
        idempotencyKey: String?,
    ): ReservationModel {
        roomType.checkGuestCount(guestCount)

        val dates = period.datesToReserve()
        val orderedRates = alignToDates(rates, dates, roomType.roomTypeId) { it.roomTypeId to it.date }
        val priceBeforeDiscount = priceCalculator.totalPrice(orderedRates)
        val discountAmount = discount?.discount(priceBeforeDiscount) ?: Money.ZERO
        val totalPrice = priceBeforeDiscount - discountAmount

        return ReservationModel.create(
            userId = userId,
            property = property,
            roomType = roomType,
            period = period,
            guestCount = guestCount,
            guest = guest,
            priceBeforeDiscount = priceBeforeDiscount,
            discountAmount = discountAmount,
            totalPrice = totalPrice,
            couponId = couponId,
            idempotencyKey = idempotencyKey,
        )
    }

    /**
     * 예약을 CANCELLED 로 전이한다(재고 복원은 호출자가 [com.stayloop.domain.inventory.RoomInventoryReserver]
     * 로 수행). 합법 전이가 아니면(예: CHECKED_IN 이후) [ReservationModel.cancel] 이 CONFLICT 로 막는다.
     */
    fun cancel(reservation: ReservationModel, now: LocalDateTime) {
        reservation.cancel(now)
    }

    /**
     * 받은 컬렉션이 [dates] 와 정확히 1:1(같은 길이·날짜·중복 없음)이고 모두 같은 [roomTypeId] 인지
     * 검증하고 날짜 오름차순으로 정렬해 돌려준다. 누락·여분·중복·불일치는 모두 BAD_REQUEST.
     */
    private fun <T> alignToDates(
        items: List<T>,
        dates: List<LocalDate>,
        roomTypeId: Long,
        keyOf: (T) -> Pair<Long, LocalDate>,
    ): List<T> {
        val byDate = HashMap<LocalDate, T>()
        items.forEach {
            val (itemRoomTypeId, date) = keyOf(it)
            if (itemRoomTypeId != roomTypeId) {
                throw CoreException(ErrorType.BAD_REQUEST, "다른 객실 타입의 정보가 섞여 있습니다.")
            }
            if (byDate.put(date, it) != null) {
                throw CoreException(ErrorType.BAD_REQUEST, "같은 날짜($date)의 정보가 중복됩니다.")
            }
        }
        if (byDate.size != dates.size) {
            throw CoreException(ErrorType.BAD_REQUEST, "투숙 일자와 재고·요금 정보의 개수가 일치하지 않습니다.")
        }
        return dates.map {
            byDate[it] ?: throw CoreException(ErrorType.BAD_REQUEST, "정보가 없는 날짜($it)가 있습니다.")
        }
    }
}
