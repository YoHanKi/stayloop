package com.stayloop.domain.reservation

import com.stayloop.domain.inventory.DailyRoomInventoryModel
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
 * 예약 도메인 서비스. Repository 의존도 `@Transactional` 도 없다(03 §4) — 재고·요금은 호출자(Facade)가
 * 미리 조회해 인자로 주입하고, 본 서비스는 받은 컬렉션으로 규칙만 수행한다. 트랜잭션 경계는 Facade.
 */
@Service
class ReservationService(
    private val priceCalculator: ReservationPriceCalculator,
) {
    /**
     * 인원·일자·재고를 검증하고 날짜별 재고를 1 씩 차감한 뒤 PENDING 예약을 만든다.
     * 인자로 받은 [inventories] 의 엔티티는 차감되어 호출자가 그대로 영속화한다.
     */
    fun reserve(
        userId: LoginId,
        property: PropertySnapshot,
        roomType: RoomTypeSnapshot,
        period: StayPeriod,
        guestCount: Int,
        guest: GuestInfo,
        inventories: List<DailyRoomInventoryModel>,
        rates: List<DailyRoomRateModel>,
    ): ReservationModel {
        roomType.checkGuestCount(guestCount)

        val dates = period.datesToReserve()
        val orderedInventories = alignToDates(inventories, dates, roomType.roomTypeId) { it.roomTypeId to it.date }
        val orderedRates = alignToDates(rates, dates, roomType.roomTypeId) { it.roomTypeId to it.date }

        orderedInventories.forEach { it.reserveOne() }
        val totalPrice = priceCalculator.totalPrice(orderedRates)

        return ReservationModel.create(
            userId = userId,
            property = property,
            roomType = roomType,
            period = period,
            guestCount = guestCount,
            guest = guest,
            totalPrice = totalPrice,
        )
    }

    /**
     * 예약을 취소(CANCELLED 전이)하고, 차감했던 날짜별 재고를 복원한다.
     * 합법 전이가 아니면(예: CHECKED_IN 이후) [ReservationModel.cancel] 이 CONFLICT 로 막는다.
     */
    fun cancel(
        reservation: ReservationModel,
        inventories: List<DailyRoomInventoryModel>,
        now: LocalDateTime,
    ) {
        reservation.cancel(now)
        val byDate = inventories.associateBy { it.date }
        reservation.period.datesToReserve().forEach { date ->
            byDate[date]?.releaseOne()
        }
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
