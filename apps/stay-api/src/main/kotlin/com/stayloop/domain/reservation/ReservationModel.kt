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
 * 회원(`userId: LoginId`) × 객실 타입(`roomTypeId`) × 기간(`StayPeriod`) × 인원(`guestCount`) 의 예약을 표현.
 * Property / RoomType 의 *예약 시점 박제* (`PropertySnapshot` / `RoomTypeSnapshot`) 와 합산 가격(`Money`) 을 함께 보존.
 *
 * **propertyId 별도 보유** — `roomTypeId` 만으로 Property 추적이 가능하지만, ERD `reservations.property_id`
 * 컬럼이 별도로 존재하고 (`docs/design/04-erd.md §3`), 본인 자원 인가·검색·집계가 propertyId 단위로 일어나므로
 * 도메인 모델도 propertyId 를 명시적으로 보유한다 (`docs/design/03 §4` 결정).
 *
 * 도메인 가드 (생성 시):
 * - `propertyId / roomTypeId` 양수
 * - `guestCount` 양수 + `guestCount <= roomType.maxGuests` (AC-5 — 인원 초과 거절)
 * - `propertyId == property.propertyId`, `roomTypeId == roomType.roomTypeId` — FK ↔ 박제 정합 (생성 시점에 호출자
 *   실수 차단)
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
    propertyId: Long,
    roomTypeId: Long,
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

    @Column(name = "property_id", nullable = false)
    var propertyId: Long = propertyId
        protected set

    @Column(name = "room_type_id", nullable = false)
    var roomTypeId: Long = roomTypeId
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

    init {
        if (propertyId <= 0L) {
            throw CoreException(ErrorType.BAD_REQUEST, "propertyId 는 양수여야 합니다.")
        }
        if (roomTypeId <= 0L) {
            throw CoreException(ErrorType.BAD_REQUEST, "roomTypeId 는 양수여야 합니다.")
        }
        if (guestCount <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "guestCount 는 양수여야 합니다.")
        }
        if (guestCount > roomType.maxGuests) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "예약 인원($guestCount) 이 객실 최대 인원(${roomType.maxGuests}) 을 초과합니다.",
            )
        }
        if (propertyId != property.propertyId) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "propertyId 와 박제된 property.propertyId 가 일치하지 않습니다.",
            )
        }
        if (roomTypeId != roomType.roomTypeId) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "roomTypeId 와 박제된 roomType.roomTypeId 가 일치하지 않습니다.",
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
            propertyId: Long,
            roomTypeId: Long,
            property: PropertySnapshot,
            roomType: RoomTypeSnapshot,
            period: StayPeriod,
            guestCount: Int,
            guest: GuestInfo,
            totalPrice: Money,
        ): ReservationModel = ReservationModel(
            userId = userId,
            propertyId = propertyId,
            roomTypeId = roomTypeId,
            property = property,
            roomType = roomType,
            period = period,
            guestCount = guestCount,
            guest = guest,
            totalPrice = totalPrice,
        )
    }
}
