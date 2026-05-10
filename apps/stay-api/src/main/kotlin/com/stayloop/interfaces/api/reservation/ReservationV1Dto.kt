package com.stayloop.interfaces.api.reservation

import com.stayloop.application.reservation.ReservationInfo
import com.stayloop.application.reservation.command.ReserveCommand
import com.stayloop.domain.reservation.value.GuestInfo
import com.stayloop.domain.reservation.value.ReservationStatus
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.PhoneNumber
import java.time.LocalDate
import java.time.LocalDateTime

class ReservationV1Dto {
    data class CreateRequest(
        val propertyId: Long,
        val roomTypeId: Long,
        val checkIn: LocalDate,
        val checkOut: LocalDate,
        val guestCount: Int,
        val guestName: String,
        val guestPhoneNumber: String,
        /**
         * 적용할 쿠폰의 발급 인스턴스 id. **null = 쿠폰 미적용** — week4-quests 명시.
         */
        val couponId: Long? = null,
    ) {
        fun toCommand(loginId: LoginId): ReserveCommand = ReserveCommand(
            userId = loginId,
            propertyId = propertyId,
            roomTypeId = roomTypeId,
            period = StayPeriod(checkIn, checkOut),
            guestCount = guestCount,
            guest = GuestInfo(name = guestName, phoneNumber = PhoneNumber(guestPhoneNumber)),
            couponId = couponId,
        )
    }

    /**
     * 예약 응답. **week4-quests "예약 정보 스냅샷에는 쿠폰 적용 전 금액, 할인 금액, 최종 결제 금액이 모두 포함" 정합** —
     * `priceBeforeDiscount` / `discountAmount` / `totalPrice` (= 최종) + 쿠폰 박제 (`couponId / couponName / couponCode`).
     */
    data class ReservationResponse(
        val reservationId: Long,
        val userId: String,
        val propertyId: Long,
        val propertyName: String,
        val propertyAddress: String,
        val roomTypeId: Long,
        val roomTypeName: String,
        val checkIn: LocalDate,
        val checkOut: LocalDate,
        val nights: Int,
        val guestCount: Int,
        val guestName: String,
        val guestPhoneNumber: String,
        val priceBeforeDiscount: Long,
        val discountAmount: Long,
        val totalPrice: Long,
        val couponId: Long?,
        val couponName: String?,
        val couponCode: String?,
        val status: ReservationStatus,
        val cancelledAt: LocalDateTime?,
    ) {
        companion object {
            fun from(info: ReservationInfo): ReservationResponse = ReservationResponse(
                reservationId = info.reservationId,
                userId = info.userId,
                propertyId = info.propertyId,
                propertyName = info.propertyName,
                propertyAddress = info.propertyAddress,
                roomTypeId = info.roomTypeId,
                roomTypeName = info.roomTypeName,
                checkIn = info.checkIn,
                checkOut = info.checkOut,
                nights = info.nights,
                guestCount = info.guestCount,
                guestName = info.guestName,
                guestPhoneNumber = info.guestPhoneNumber,
                priceBeforeDiscount = info.priceBeforeDiscount.amount,
                discountAmount = info.discountAmount.amount,
                totalPrice = info.totalPrice.amount,
                couponId = info.couponId,
                couponName = info.couponName,
                couponCode = info.couponCode,
                status = info.status,
                cancelledAt = info.cancelledAt,
            )
        }
    }
}
