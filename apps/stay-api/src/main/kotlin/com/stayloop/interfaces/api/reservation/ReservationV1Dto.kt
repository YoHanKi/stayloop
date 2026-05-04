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
    ) {
        fun toCommand(loginId: LoginId): ReserveCommand = ReserveCommand(
            userId = loginId,
            propertyId = propertyId,
            roomTypeId = roomTypeId,
            period = StayPeriod(checkIn, checkOut),
            guestCount = guestCount,
            guest = GuestInfo(name = guestName, phoneNumber = PhoneNumber(guestPhoneNumber)),
        )
    }

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
        val totalPrice: Long,
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
                totalPrice = info.totalPrice.amount,
                status = info.status,
                cancelledAt = info.cancelledAt,
            )
        }
    }
}
