package com.stayloop.application.reservation

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.reservation.ReservationModel
import com.stayloop.domain.reservation.value.ReservationStatus
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 예약 응답 Info. (`docs/plan/week2-3.md §⑧ Phase C`)
 *
 * 박제 VO(`PropertySnapshot` / `RoomTypeSnapshot`) 의 직렬화된 값을 그대로 노출 — 예약 시점 정보를 영수증
 * 흐름에서 그대로 사용. 도메인 모델 자체는 노출하지 않는다 (Application/Interface 경계 유지).
 */
data class ReservationInfo(
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
    val totalPrice: Money,
    val status: ReservationStatus,
    val cancelledAt: LocalDateTime?,
) {
    companion object {
        fun from(model: ReservationModel): ReservationInfo = ReservationInfo(
            reservationId = model.id,
            userId = model.userId.value,
            propertyId = model.property.propertyId,
            propertyName = model.property.propertyName,
            propertyAddress = model.property.propertyAddress,
            roomTypeId = model.roomType.roomTypeId,
            roomTypeName = model.roomType.roomTypeName,
            checkIn = model.period.checkIn,
            checkOut = model.period.checkOut,
            nights = model.period.nights(),
            guestCount = model.guestCount,
            guestName = model.guest.name,
            guestPhoneNumber = model.guest.phoneNumber.value,
            totalPrice = model.totalPrice,
            status = model.status,
            cancelledAt = model.cancelledAt,
        )
    }
}
