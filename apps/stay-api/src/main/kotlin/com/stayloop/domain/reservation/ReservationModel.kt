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
 * 예약 Aggregate Root. 회원 × 객실 타입 × 기간 × 인원, *예약 시점 박제* (Property/RoomType/Coupon Snapshot) +
 * 할인 4 컬럼 (priceBeforeDiscount / discountAmount / couponSnapshot / totalPrice = price - discount).
 *
 * `couponId` / `propertyId` / `roomTypeId` 별도 컬럼 X — Snapshot VO 가 단독 매핑 (JPA 컬럼 중복 매핑 회피),
 * 외부 노출은 위임 프로퍼티. 도메인 가드 (init): guestCount > 0 + ≤ maxGuests, discount ≤ priceBefore,
 * total = priceBefore - discount, `discount == 0 ↔ couponSnapshot == null`. 상태 메서드는 canTransitTo 검증
 * 후 변경 (Strong Exception Safety), 시각이 필요한 cancel 은 호출자가 Clock 주입.
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

    val propertyId: Long get() = property.propertyId
    val roomTypeId: Long get() = roomType.roomTypeId
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

    fun confirm() = transitTo(ReservationStatus.CONFIRMED)
    fun checkIn() = transitTo(ReservationStatus.CHECKED_IN)
    fun checkOut() = transitTo(ReservationStatus.CHECKED_OUT)
    fun markNoShow() = transitTo(ReservationStatus.NO_SHOW)

    /** 취소. cancelledAt 시각은 호출자 주입 (Clock 또는 테스트 고정값). */
    fun cancel(now: LocalDateTime) {
        transitTo(ReservationStatus.CANCELLED)
        cancelledAt = now
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
         * 쿠폰 미적용 / 적용 양쪽 동일 팩토리. discount = ZERO + couponSnapshot = null 이 미적용.
         * priceBeforeDiscount default = totalPrice (쿠폰 미적용 의미, 기존 reserve 흐름 호환).
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
