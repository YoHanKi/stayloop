package com.stayloop.domain.reservation

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.common.value.Money
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
 * 예약 Aggregate Root. (`docs/design/03-class-diagram.md §4`, `04-erd.md §3`, `docs/plan/week2-3.md §⑦`)
 *
 * 회원(`userId: LoginId`) × 객실 타입 × 기간(`StayPeriod`) × 인원(`guestCount`) 의 예약을 표현.
 * Property / RoomType 의 *예약 시점 박제* (`PropertySnapshot` / `RoomTypeSnapshot`) 와 합산 가격(`Money`) 을 함께 보존.
 *
 * **`propertyId` / `roomTypeId` 는 박제 VO 위임** — ERD 의 `reservations.property_id` / `room_type_id` 컬럼은
 * `@Embedded` 박제 VO 의 `@Column(name = "property_id")` / `@Column(name = "room_type_id")` 가 *유일 매핑* 이며,
 * 모델 외부에는 `val propertyId: Long get() = property.propertyId` 형태의 *위임 프로퍼티* 로 노출. 동일 컬럼을
 * 모델 본체와 박제 VO 가 동시에 매핑하면 Hibernate bootstrap 시 *컬럼 중복 매핑 예외* 가 발생하므로 SSOT 는
 * 박제 VO 한 곳 (verify-code §6 — JPA 컬럼 중복 매핑 가드).
 *
 * 도메인 가드 (생성 시):
 * - `guestCount` 양수 + `guestCount <= roomType.maxGuests` (AC-5 — 인원 초과 거절)
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
    totalPrice: Money,
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
    @AttributeOverride(name = "amount", column = Column(name = "total_price", nullable = false))
    var totalPrice: Money = totalPrice
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

        fun create(
            userId: LoginId,
            property: PropertySnapshot,
            roomType: RoomTypeSnapshot,
            period: StayPeriod,
            guestCount: Int,
            guest: GuestInfo,
            totalPrice: Money,
        ): ReservationModel = ReservationModel(
            userId = userId,
            property = property,
            roomType = roomType,
            period = period,
            guestCount = guestCount,
            guest = guest,
            totalPrice = totalPrice,
        )
    }
}
