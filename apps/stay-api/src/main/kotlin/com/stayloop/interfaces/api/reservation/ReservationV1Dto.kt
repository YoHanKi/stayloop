package com.stayloop.interfaces.api.reservation

import com.stayloop.application.reservation.ReservationInfo
import com.stayloop.application.reservation.command.ReserveCommand
import com.stayloop.domain.user.value.LoginId
import java.math.BigDecimal
import java.time.LocalDate

class ReservationV1Dto {
    data class ReserveRequest(
        val propertyId: Long,
        val roomTypeId: Long,
        val checkIn: LocalDate,
        val checkOut: LocalDate,
        val guestCount: Int,
        val guestName: String,
        val guestPhoneNumber: String,
    ) {
        fun toCommand(loginId: LoginId): ReserveCommand =
            ReserveCommand(
                loginId = loginId,
                propertyId = propertyId,
                roomTypeId = roomTypeId,
                checkIn = checkIn,
                checkOut = checkOut,
                guestCount = guestCount,
                guestName = guestName,
                guestPhoneNumber = guestPhoneNumber,
            )
    }

    data class ReservationResponse(
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
        val totalPrice: BigDecimal,
        val status: String,
        val cancelledAt: String?,
    ) {
        companion object {
            fun from(info: ReservationInfo): ReservationResponse =
                ReservationResponse(
                    reservationId = info.reservationId,
                    propertyId = info.propertyId,
                    propertyName = info.propertyName,
                    roomTypeId = info.roomTypeId,
                    roomTypeName = info.roomTypeName,
                    checkIn = info.checkIn,
                    checkOut = info.checkOut,
                    nights = info.nights,
                    guestCount = info.guestCount,
                    guestName = info.guestName,
                    totalPrice = info.totalPrice,
                    status = info.status,
                    cancelledAt = info.cancelledAt,
                )
        }
    }
}
