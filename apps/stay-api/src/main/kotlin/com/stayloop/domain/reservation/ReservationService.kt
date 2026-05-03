package com.stayloop.domain.reservation

import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.rate.ReservationPriceCalculator
import com.stayloop.domain.reservation.value.GuestInfo
import com.stayloop.domain.reservation.value.PropertySnapshot
import com.stayloop.domain.reservation.value.RoomTypeSnapshot
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Reservation 도메인 서비스. (`docs/design/03-class-diagram.md §4`, `docs/plan/week2-3.md §⑦`)
 *
 * **Repository 의존하지 않음** — Inventory / Rate 는 *호출자(Facade) 가 미리 조회해 인자로 주입*. 도메인 서비스는
 * 영속성 책임을 지지 않으며, 트랜잭션 / 본인 자원 인가 / `@Transactional` 은 모두 Facade 책임 (`docs/design/03 §4`
 * + CLAUDE.md 도메인 ↔ application 분리 정책).
 *
 * **Property / RoomType 박제 변환은 호출자 책임** — 시그니처가 `PropertySnapshot` / `RoomTypeSnapshot` 를 받는다.
 * Service 가 PropertyModel / RoomTypeModel 을 import 하지 않게 — 도메인 모듈 내부의 *횡단 의존* 을 줄인다.
 *
 * 본 라운드 검증 책임 (AC-3, AC-4, AC-5):
 * - **AC-5**: `guestCount > roomTypeSnapshot.maxGuests` 거절 (도메인 1차 가드, ReservationModel.init 도 중복 검증).
 * - **AC-3**: `period.datesToReserve()` (체크아웃 당일 제외) 의 모든 일자가 `inventories` / `rates` 에 매칭되어야 함 —
 *   누락 일자 / 빈 컬렉션 거절. 정확한 일자 set 동치성을 검증.
 * - **AC-4** 의 *부분 차감 후 실패 시 롤백* 은 Service 가 아니라 *Facade 의 `@Transactional`* 책임 — Service 는 단순히
 *   `reserveOne()` 을 호출하고 도중에 throw 가 발생하면 그대로 전파한다 (호출 시점까지 차감된 inventories 의 부분 상태는
 *   같은 TX 안에서 자동 롤백).
 *
 * **합산 가격은 `ReservationPriceCalculator` 위임** — 도메인 서비스끼리 의존 OK (둘 다 도메인).
 *
 * `cancel(reservation, inventories, now)` — 상태 전이 검증은 `reservation.cancel(now)` 가 자체 처리. inventory
 * `releaseOne()` 의 멱등 깨짐(이미 0 인 행을 복원 시도) 도 도메인 모델이 자체 CONFLICT 로 거절 — Service 는 단순 위임.
 */
@Service
class ReservationService(
    private val priceCalculator: ReservationPriceCalculator,
) {
    /**
     * 예약 도메인 트랜잭션 — 인원/일자 검증 → 재고 차감 → 요금 합산 → ReservationModel 생성(PENDING).
     * **반환은 (변경된 inventories, 신규 reservation)** — 호출자(Facade) 가 같은 TX 에서 saveAll(inventories) +
     * save(reservation) 로 영속화한다.
     */
    fun reserve(
        userId: LoginId,
        propertySnapshot: PropertySnapshot,
        roomTypeSnapshot: RoomTypeSnapshot,
        period: StayPeriod,
        guestCount: Int,
        guest: GuestInfo,
        inventories: List<DailyRoomInventoryModel>,
        rates: List<DailyRoomRateModel>,
    ): Pair<List<DailyRoomInventoryModel>, ReservationModel> {
        // 1. 인원 검증 (AC-5)
        if (guestCount > roomTypeSnapshot.maxGuests) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "예약 인원($guestCount) 이 객실 최대 인원(${roomTypeSnapshot.maxGuests}) 을 초과합니다.",
            )
        }

        // 2. 일자 누락 / 빈 컬렉션 검증 (AC-3)
        val expectedDates = period.datesToReserve().toSet()
        if (expectedDates.isEmpty()) {
            // StayPeriod.init 가 1박 이상을 보장하지만 방어적으로 한 번 더 명시
            throw CoreException(ErrorType.BAD_REQUEST, "예약 가능 일자가 비어 있습니다.")
        }
        if (inventories.map { it.date }.toSet() != expectedDates) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "재고 일자가 예약 기간과 일치하지 않습니다 (누락 또는 초과 일자 존재).",
            )
        }
        if (rates.map { it.date }.toSet() != expectedDates) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "요금 일자가 예약 기간과 일치하지 않습니다 (누락 또는 초과 일자 존재).",
            )
        }

        // 3. 재고 차감 — DailyRoomInventoryModel.reserveOne() 가 가용 0 일 때 CONFLICT 로 자체 거절
        // 도중 throw 시 Facade 의 @Transactional 이 롤백 (AC-4)
        inventories.forEach { it.reserveOne() }

        // 4. 요금 합산
        val total = priceCalculator.totalPrice(rates)

        // 5. Reservation 생성 (PENDING)
        val reservation = ReservationModel.create(
            userId = userId,
            propertyId = propertySnapshot.propertyId,
            roomTypeId = roomTypeSnapshot.roomTypeId,
            property = propertySnapshot,
            roomType = roomTypeSnapshot,
            period = period,
            guestCount = guestCount,
            guest = guest,
            totalPrice = total,
        )

        return inventories to reservation
    }

    /**
     * 예약 취소 — 상태 전이(`reservation.cancel(now)` 가 자체 검증) + inventory 복원.
     * 호출자(Facade) 가 inventory 를 미리 조회 후 인자로 주입.
     */
    fun cancel(
        reservation: ReservationModel,
        inventories: List<DailyRoomInventoryModel>,
        now: LocalDateTime,
    ): Pair<List<DailyRoomInventoryModel>, ReservationModel> {
        // 1. 상태 전이 검증 + cancelledAt 박제 (CHECKED_IN 이후라면 ReservationModel.cancel 이 CONFLICT)
        reservation.cancel(now)

        // 2. inventory 복원 — DailyRoomInventoryModel.releaseOne() 이 reservedRooms == 0 일 때 자체 CONFLICT
        inventories.forEach { it.releaseOne() }

        return inventories to reservation
    }
}
