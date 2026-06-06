package com.stayloop.application.property.command

import com.stayloop.domain.reservation.value.StayPeriod
import java.time.LocalDate

/**
 * 특정 숙소의 기간·인원별 가용 객실 조회 조건.
 */
data class RoomAvailabilityQuery(
    val propertyId: Long,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val guestCount: Int,
) {
    fun period(): StayPeriod = StayPeriod(checkIn, checkOut)
}
