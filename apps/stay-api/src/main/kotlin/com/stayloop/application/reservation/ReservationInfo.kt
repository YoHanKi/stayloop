package com.stayloop.application.reservation

import com.stayloop.domain.reservation.ReservationModel
import java.math.BigDecimal

/**
 * 예약 결과·조회 결과. 박제 VO 의 직렬화 값만 노출하고 도메인 모델을 인터페이스로 흘리지 않는다.
 */
data class ReservationInfo(
    val reservationId: Long,
    val propertyId: Long,
    val propertyName: String,
    val roomTypeId: Long,
    val roomTypeName: String,
    val checkIn: String,
    val checkOut: String,
    val nights: Long,
    val guestCount: Int,
    val guestName: String,
    val priceBeforeDiscount: BigDecimal,
    val discountAmount: BigDecimal,
    val totalPrice: BigDecimal,
    val couponId: Long?,
    val status: String,
    val cancelledAt: String?,
) {
    companion object {
        fun from(reservation: ReservationModel): ReservationInfo =
            ReservationInfo(
                reservationId = reservation.id,
                propertyId = reservation.propertyId,
                propertyName = reservation.property.name,
                roomTypeId = reservation.roomTypeId,
                roomTypeName = reservation.roomType.name,
                checkIn = reservation.period.checkIn.toString(),
                checkOut = reservation.period.checkOut.toString(),
                nights = reservation.period.nights(),
                guestCount = reservation.guestCount,
                guestName = reservation.guest.name,
                priceBeforeDiscount = reservation.priceBeforeDiscount.amount,
                discountAmount = reservation.discountAmount.amount,
                totalPrice = reservation.totalPrice.amount,
                couponId = reservation.couponId,
                status = reservation.status.name,
                cancelledAt = reservation.cancelledAt?.toString(),
            )
    }
}
