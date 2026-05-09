package com.stayloop.domain.reservation

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.common.value.Money
import com.stayloop.domain.reservation.value.CouponSnapshot
import com.stayloop.domain.reservation.value.GuestInfo
import com.stayloop.domain.reservation.value.PropertySnapshot
import com.stayloop.domain.reservation.value.ReservationStatus
import com.stayloop.domain.reservation.value.RoomTypeSnapshot
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 예약 Aggregate Root. (`docs/design/03-class-diagram.md §4`, `04-erd.md §3`, `docs/plan/week2-3.md §⑦`,
 * `docs/plan/week4.md` ② Commit 1)
 *
 * 회원(`userId: LoginId`) × 객실 타입 × 기간(`StayPeriod`) × 인원(`guestCount`) 의 예약을 표현.
 * Property / RoomType / **Coupon** 의 *예약 시점 박제* (`PropertySnapshot` / `RoomTypeSnapshot` /
 * `CouponSnapshot`) 와 합산 가격(`Money`) 을 함께 보존.
 *
 * **할인 박제 4 컬럼 (4주차 ② 합류)**:
 * - `priceBeforeDiscount` — 일자별 요금 합산 (할인 *전*) — 영수증/회계 추적의 SSOT
 * - `discountAmount` — 실제 차감된 할인 금액 (정액 = 그대로, 정률 = 계산 결과)
 * - `couponSnapshot` — `CouponSnapshot?` (쿠폰 미적용이면 null)
 * - `totalPrice` — 기존 컬럼 의미 *변경* — 이제 *최종 결제 금액* (= `priceBeforeDiscount - discountAmount`)
 *
 * **`couponId` 는 별도 컬럼으로 두지 않는다** — `couponSnapshot.couponId` 가 *유일 매핑* (PropertySnapshot 의
 * `propertyId` 컬럼 매핑 패턴 답습; verify-code §6 컬럼 중복 매핑 가드). 단, `couponSnapshot == null` 이면 컬럼 자체가
 * NULL — *외부 위임 프로퍼티* `val couponId: Long? get() = couponSnapshot?.couponId` 형태로 노출.
 *
 * **`propertyId` / `roomTypeId` 는 박제 VO 위임** — ERD 의 `reservations.property_id` / `room_type_id` 컬럼은
 * `@Embedded` 박제 VO 의 `@Column(name = "property_id")` / `@Column(name = "room_type_id")` 가 *유일 매핑* 이며,
 * 모델 외부에는 `val propertyId: Long get() = property.propertyId` 형태의 *위임 프로퍼티* 로 노출. 동일 컬럼을
 * 모델 본체와 박제 VO 가 동시에 매핑하면 Hibernate bootstrap 시 *컬럼 중복 매핑 예외* 가 발생하므로 SSOT 는
 * 박제 VO 한 곳 (verify-code §6 — JPA 컬럼 중복 매핑 가드).
 *
 * 도메인 가드 (생성 시):
 * - `guestCount` 양수 + `guestCount <= roomType.maxGuests` (AC-5 — 인원 초과 거절)
 * - **할인 산술 정합성**:
 *   - `discountAmount <= priceBeforeDiscount` (할인이 결제 금액 초과 X — 환급 형태 차단)
 *   - `totalPrice == priceBeforeDiscount - discountAmount` (회계 SSOT 정합)
 *   - `discountAmount == 0` ↔ `couponSnapshot == null` (쿠폰 미적용/적용의 일관성 — silent 사고 차단)
 * - `propertyId / roomTypeId` 양수성은 `PropertySnapshot` / `RoomTypeSnapshot` VO 의 init 이 자체 검증 (위임)
 * - `totalPrice` 음수 가드는 Money VO 책임
 *
 * 상태 메서드는 `ReservationStatus.canTransitTo(...)` 검증 후 변경한다 (Strong Exception Safety) — 검증 실패 시
 * `CONFLICT` 로 거절하고 객체 상태는 불변. `cancel(now)` 만 시각 박제가 필요하므로 호출자가 시각을 주입한다
 * (테스트는 임의 시각, 운영은 `Clock.now()`).
 */
@Entity
@Table(name = "reservations")
class ReservationModel internal constructor(
    userId: LoginId,
    property: PropertySnapshot,
    roomType: RoomTypeSnapshot,
    period: StayPeriod,
    guestCount: Int,
    guest: GuestInfo,
    priceBeforeDiscount: Money,
    discountAmount: Money,
    totalPrice: Money,
    couponSnapshot: CouponSnapshot?,
) : BaseEntity() {

    @Embedded
    @AttributeOverride(name = "value", column = Column(name = "user_login_id", nullable = false, length = 20))
    var userId: LoginId = userId
        protected set

    @Embedded
    var property: PropertySnapshot = property
        protected set

    @Embedded
    var roomType: RoomTypeSnapshot = roomType
        protected set

    @Embedded
    var period: StayPeriod = period
        protected set

    @Column(name = "guest_count", nullable = false)
    var guestCount: Int = guestCount
        protected set

    @Embedded
    var guest: GuestInfo = guest
        protected set

    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "price_before_discount", nullable = false))
    var priceBeforeDiscount: Money = priceBeforeDiscount
        protected set

    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "discount_amount", nullable = false))
    var discountAmount: Money = discountAmount
        protected set

    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "total_price", nullable = false))
    var totalPrice: Money = totalPrice
        protected set

    @Embedded
    var couponSnapshot: CouponSnapshot? = couponSnapshot
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = STATUS_COLUMN_LENGTH)
    var status: ReservationStatus = ReservationStatus.PENDING
        protected set

    @Column(name = "cancelled_at", nullable = true)
    var cancelledAt: LocalDateTime? = null
        protected set

    /** `property.propertyId` 위임 — 컬럼은 `PropertySnapshot.@Column(name="property_id")` 가 단독 매핑. */
    val propertyId: Long get() = property.propertyId

    /** `roomType.roomTypeId` 위임 — 컬럼은 `RoomTypeSnapshot.@Column(name="room_type_id")` 가 단독 매핑. */
    val roomTypeId: Long get() = roomType.roomTypeId

    /** `couponSnapshot.couponId` 위임 — 쿠폰 미적용이면 null. */
    val couponId: Long? get() = couponSnapshot?.couponId

    init {
        if (guestCount <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "guestCount 는 양수여야 합니다.")
        }
        if (guestCount > roomType.maxGuests) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "예약 인원($guestCount) 이 객실 최대 인원(${roomType.maxGuests}) 을 초과합니다.",
            )
        }
        if (discountAmount > priceBeforeDiscount) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "할인 금액은 결제 금액을 초과할 수 없습니다.",
            )
        }
        if (totalPrice != priceBeforeDiscount - discountAmount) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "할인 산술이 일치하지 않습니다 (totalPrice 가 priceBeforeDiscount - discountAmount 와 다름).",
            )
        }
        // discountAmount == 0 ↔ couponSnapshot == null — silent 사고 차단
        if (discountAmount.isZero() && couponSnapshot != null) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "할인 금액이 0 인데 쿠폰 박제가 존재합니다 (불일치).",
            )
        }
        if (!discountAmount.isZero() && couponSnapshot == null) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "할인 금액이 양수인데 쿠폰 박제가 비어 있습니다 (불일치).",
            )
        }
    }

    /**
     * 결제 완료 후 PENDING → CONFIRMED 전이. 결제 합류 시점(5주차+) 에 결제 검증 결과로 호출됨.
     */
    fun confirm() {
        transitTo(ReservationStatus.CONFIRMED)
    }

    /**
     * 입실 — CONFIRMED → CHECKED_IN.
     */
    fun checkIn() {
        transitTo(ReservationStatus.CHECKED_IN)
    }

    /**
     * 퇴실 — CHECKED_IN → CHECKED_OUT.
     */
    fun checkOut() {
        transitTo(ReservationStatus.CHECKED_OUT)
    }

    /**
     * 취소 — PENDING / CONFIRMED → CANCELLED. `cancelledAt` 시각도 함께 박제.
     * 호출자가 시각을 주입 (운영: `Clock.now()`, 테스트: 고정값).
     */
    fun cancel(now: LocalDateTime) {
        transitTo(ReservationStatus.CANCELLED)
        cancelledAt = now
    }

    /**
     * NO_SHOW — CONFIRMED → NO_SHOW. 5~6주차 배치 트리거 영역의 자리.
     */
    fun markNoShow() {
        transitTo(ReservationStatus.NO_SHOW)
    }

    private fun transitTo(next: ReservationStatus) {
        if (!status.canTransitTo(next)) {
            throw CoreException(
                ErrorType.CONFLICT,
                "현재 상태($status) 에서 $next 로 전이할 수 없습니다.",
            )
        }
        status = next
    }

    companion object {
        private const val STATUS_COLUMN_LENGTH: Int = 20

        /**
         * 쿠폰 미적용 / 적용 양쪽을 동일 팩토리로 만든다 — `discountAmount = ZERO` + `couponSnapshot = null` 이
         * 미적용 케이스. `Discount` VO 의 `none(beforeDiscount)` 와 의미적으로 일관.
         *
         * **마이그레이션 호환성** — `priceBeforeDiscount` 가 명시되지 않으면 `totalPrice` 와 동일 (쿠폰 미적용
         * 의미). 본 라운드 ② commit 1 의 *기존 reserve 흐름은 쿠폰 미적용으로 동작* 정합 (`docs/plan/week4.md`).
         */
        fun create(
            userId: LoginId,
            property: PropertySnapshot,
            roomType: RoomTypeSnapshot,
            period: StayPeriod,
            guestCount: Int,
            guest: GuestInfo,
            totalPrice: Money,
            priceBeforeDiscount: Money = totalPrice,
            discountAmount: Money = Money.ZERO,
            couponSnapshot: CouponSnapshot? = null,
        ): ReservationModel = ReservationModel(
            userId = userId,
            property = property,
            roomType = roomType,
            period = period,
            guestCount = guestCount,
            guest = guest,
            priceBeforeDiscount = priceBeforeDiscount,
            discountAmount = discountAmount,
            totalPrice = totalPrice,
            couponSnapshot = couponSnapshot,
        )
    }
}
