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
 * 예약 Aggregate Root. 숙소·객실 정보는 [PropertySnapshot] / [RoomTypeSnapshot] 으로 박제해
 * 원본 변경에 영향받지 않는다(03 §6). propertyId / roomTypeId 는 스냅샷에 위임해 중복 매핑을 피한다.
 *
 * 상태 변경 메서드는 모두 [ReservationStatus.canTransitTo] 를 거쳐 합법 전이만 통과시킨다.
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
    @AttributeOverride(name = "amount", column = Column(name = "total_price", nullable = false, precision = 19, scale = 2))
    var totalPrice: Money = totalPrice
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: ReservationStatus = ReservationStatus.PENDING
        protected set

    @Column(name = "cancelled_at")
    var cancelledAt: LocalDateTime? = null
        protected set

    val propertyId: Long get() = property.propertyId
    val roomTypeId: Long get() = roomType.roomTypeId

    fun confirm() = transitTo(ReservationStatus.CONFIRMED)

    fun checkIn() = transitTo(ReservationStatus.CHECKED_IN)

    fun checkOut() = transitTo(ReservationStatus.CHECKED_OUT)

    fun markNoShow() = transitTo(ReservationStatus.NO_SHOW)

    fun cancel(now: LocalDateTime) {
        transitTo(ReservationStatus.CANCELLED)
        cancelledAt = now
    }

    private fun transitTo(next: ReservationStatus) {
        if (!status.canTransitTo(next)) {
            throw CoreException(ErrorType.CONFLICT, "예약 상태를 $status 에서 $next 로 바꿀 수 없습니다.")
        }
        status = next
    }

    companion object {
        fun create(
            userId: LoginId,
            property: PropertySnapshot,
            roomType: RoomTypeSnapshot,
            period: StayPeriod,
            guestCount: Int,
            guest: GuestInfo,
            totalPrice: Money,
        ): ReservationModel =
            ReservationModel(
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
