package com.stayloop.application.reservation.command

import com.stayloop.domain.reservation.value.GuestInfo
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.PhoneNumber
import java.time.LocalDate

data class ReserveCommand(
    val loginId: LoginId,
    val propertyId: Long,
    val roomTypeId: Long,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val guestCount: Int,
    val guestName: String,
    val guestPhoneNumber: String,
    val issuedCouponId: Long? = null,
    val idempotencyKey: String? = null,
) {
    fun period(): StayPeriod = StayPeriod(checkIn, checkOut)

    fun guestInfo(): GuestInfo = GuestInfo(name = guestName, phoneNumber = PhoneNumber(guestPhoneNumber))
}
