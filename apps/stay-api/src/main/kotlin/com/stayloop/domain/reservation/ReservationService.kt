package com.stayloop.domain.reservation

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.coupon.value.Discount
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.rate.ReservationPriceCalculator
import com.stayloop.domain.reservation.value.CouponSnapshot
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
 * - **AC-5**: `guestCount` 양수 + `guestCount <= roomTypeSnapshot.maxGuests` 거절 (도메인 1차 가드,
 *   ReservationModel.init 도 중복 검증). **부수효과(`reserveOne()`) 이전에 실패시켜야** 부분 변경을 막는다
 *   (verify-code §6 — cheap input early guard).
 * - **AC-3**: `period.datesToReserve()` (체크아웃 당일 제외) 의 모든 일자가 `inventories` / `rates` 에 *1:1 매칭*
 *   되어야 함 — 누락 / 초과 / **중복** 일자 거절. set 비교만으로는 중복(`[5/10, 5/10, 5/11]`)을 못 잡으므로
 *   `size == expectedDates.size && set == expectedDates` 페어 (verify-code §16-A — set 비교의 중복 사각지대).
 * - 외부 주입 컬렉션 일관성 — `inventory.roomTypeId == roomTypeSnapshot.roomTypeId` 도 검증. Facade 호출자 실수로
 *   다른 객실의 inventory 를 주입하면 reserveOne() 이 엉뚱한 자원을 차감.
 * - **AC-4** 의 *부분 차감 후 실패 시 롤백* 은 Service 가 아니라 *Facade 의 `@Transactional`* 책임 — Service 는 단순히
 *   `reserveOne()` 을 호출하고 도중에 throw 가 발생하면 그대로 전파한다 (호출 시점까지 차감된 inventories 의 부분 상태는
 *   같은 TX 안에서 자동 롤백).
 *
 * **합산 가격은 `ReservationPriceCalculator` 위임** — 도메인 서비스끼리 의존 OK (둘 다 도메인).
 *
 * `cancel(reservation, inventories, now)` — 상태 전이 검증은 `reservation.cancel(now)` 가 자체 처리. inventory
 * `releaseOne()` 의 멱등 깨짐(이미 0 인 행을 복원 시도) 도 도메인 모델이 자체 CONFLICT 로 거절. **다만 호출자가
 * 잘못된 inventory 리스트(roomTypeId 불일치 / 일자 불일치)를 주입하면 다른 예약의 재고를 잘못 복원하는 정합성
 * 오류** 가능 — `reserve()` 와 동일하게 일자/roomTypeId 가드를 둔 뒤 release 한다.
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
        discount: Discount? = null,
        couponSnapshot: CouponSnapshot? = null,
    ): Pair<List<DailyRoomInventoryModel>, ReservationModel> {
        // 1. 인원 검증 (AC-5) — cheap input 은 부수효과 이전에 끝낸다 (verify-code §6)
        if (guestCount <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "예약 인원($guestCount) 은 양수여야 합니다.")
        }
        if (guestCount > roomTypeSnapshot.maxGuests) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "예약 인원($guestCount) 이 객실 최대 인원(${roomTypeSnapshot.maxGuests}) 을 초과합니다.",
            )
        }

        // 2. 일자 1:1 매칭 검증 (AC-3) — size + set 페어로 중복 일자도 차단 (verify-code §16-A)
        val expectedDates = period.datesToReserve().toSet()
        if (expectedDates.isEmpty()) {
            // StayPeriod.init 가 1박 이상을 보장하지만 방어적으로 한 번 더 명시
            throw CoreException(ErrorType.BAD_REQUEST, "예약 가능 일자가 비어 있습니다.")
        }
        validateDailyCollectionMatchesPeriod(
            label = "재고",
            items = inventories,
            itemDate = { it.date },
            itemRoomTypeId = { it.roomTypeId },
            expectedRoomTypeId = roomTypeSnapshot.roomTypeId,
            expectedDates = expectedDates,
        )
        validateDailyCollectionMatchesPeriod(
            label = "요금",
            items = rates,
            itemDate = { it.date },
            itemRoomTypeId = { it.roomTypeId },
            expectedRoomTypeId = roomTypeSnapshot.roomTypeId,
            expectedDates = expectedDates,
        )

        // 2-A. discount ↔ couponSnapshot 일관성 — 둘 다 null 또는 둘 다 non-null (silent 사고 차단)
        if ((discount == null) != (couponSnapshot == null)) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "discount 와 couponSnapshot 은 함께 null 또는 함께 non-null 이어야 합니다.",
            )
        }

        // 3. 재고 차감 — DailyRoomInventoryModel.reserveOne() 가 가용 0 일 때 CONFLICT 로 자체 거절
        // 도중 throw 시 Facade 의 @Transactional 이 롤백 (AC-4)
        inventories.forEach { it.reserveOne() }

        // 4. 요금 합산 — discount 가 주어지면 calculator 가 합산 ↔ discount.beforeDiscount 정합 검증 후 finalPrice 반환
        val total = priceCalculator.totalPrice(rates, discount)
        val priceBefore: Money = discount?.beforeDiscount ?: total
        val discountAmount: Money = discount?.amount ?: Money.ZERO

        // 5. Reservation 생성 (PENDING)
        val reservation = ReservationModel.create(
            userId = userId,
            property = propertySnapshot,
            roomType = roomTypeSnapshot,
            period = period,
            guestCount = guestCount,
            guest = guest,
            totalPrice = total,
            priceBeforeDiscount = priceBefore,
            discountAmount = discountAmount,
            couponSnapshot = couponSnapshot,
        )

        return inventories to reservation
    }

    /**
     * 예약 취소 — 상태 전이(`reservation.cancel(now)` 가 자체 검증) + inventory 복원.
     * 호출자(Facade) 가 inventory 를 미리 조회 후 인자로 주입. **release 전 inventory ↔ reservation 일치
     * 가드** — roomTypeId / 일자 set 동치 (verify-code §16-A — 외부 주입 컬렉션 일관성).
     */
    fun cancel(
        reservation: ReservationModel,
        inventories: List<DailyRoomInventoryModel>,
        now: LocalDateTime,
    ): Pair<List<DailyRoomInventoryModel>, ReservationModel> {
        // 1. inventories 가 reservation 과 일치하는지 검증 (잘못된 자원 복원 차단)
        val expectedDates = reservation.period.datesToReserve().toSet()
        validateDailyCollectionMatchesPeriod(
            label = "재고",
            items = inventories,
            itemDate = { it.date },
            itemRoomTypeId = { it.roomTypeId },
            expectedRoomTypeId = reservation.roomTypeId,
            expectedDates = expectedDates,
        )

        // 2. 상태 전이 검증 + cancelledAt 박제 (CHECKED_IN 이후라면 ReservationModel.cancel 이 CONFLICT)
        reservation.cancel(now)

        // 3. inventory 복원 — DailyRoomInventoryModel.releaseOne() 이 reservedRooms == 0 일 때 자체 CONFLICT
        inventories.forEach { it.releaseOne() }

        return inventories to reservation
    }

    /**
     * 외부 주입 일자별 컬렉션 (inventories / rates) 이 (a) 같은 roomTypeId, (b) 기대 일자 set 과 size+set 동치
     * 인지 검증한다. 중복 일자(`[5/10, 5/10, 5/11]`) 는 set 비교에서 통과해도 size 가 다르므로 거절.
     */
    private fun <T> validateDailyCollectionMatchesPeriod(
        label: String,
        items: List<T>,
        itemDate: (T) -> java.time.LocalDate,
        itemRoomTypeId: (T) -> Long,
        expectedRoomTypeId: Long,
        expectedDates: Set<java.time.LocalDate>,
    ) {
        if (items.any { itemRoomTypeId(it) != expectedRoomTypeId }) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "$label 의 roomTypeId 가 예약 객실과 일치하지 않습니다.",
            )
        }
        val dates = items.map(itemDate)
        if (dates.size != expectedDates.size || dates.toSet() != expectedDates) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "$label 일자가 예약 기간과 1:1 매칭되지 않습니다 (누락 / 초과 / 중복 일자 존재).",
            )
        }
    }
}
