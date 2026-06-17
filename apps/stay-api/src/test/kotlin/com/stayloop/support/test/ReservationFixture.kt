package com.stayloop.support.test

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.property.value.PropertyCategory
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.reservation.ReservationModel
import com.stayloop.domain.reservation.value.GuestInfo
import com.stayloop.domain.reservation.value.PropertySnapshot
import com.stayloop.domain.reservation.value.RoomTypeSnapshot
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.PhoneNumber
import java.time.LocalDate

/**
 * 예약 도메인 테스트용 기본 픽스처.
 */
object ReservationFixture {
    const val ROOM_TYPE_ID = 10L
    val USER = LoginId("alice01")
    val GUEST = GuestInfo("홍길동", PhoneNumber("010-1234-5678"))

    fun property(): PropertySnapshot = PropertySnapshot(1L, "스테이루프 호텔", PropertyCategory.HOTEL, "seoul")

    fun roomType(maxGuests: Int = 4): RoomTypeSnapshot = RoomTypeSnapshot(ROOM_TYPE_ID, "디럭스 더블", 2, maxGuests)

    fun period(checkIn: LocalDate = LocalDate.of(2026, 6, 1), checkOut: LocalDate = LocalDate.of(2026, 6, 3)): StayPeriod =
        StayPeriod(checkIn, checkOut)

    fun inventory(date: LocalDate, total: Int = 2, reserved: Int = 0): DailyRoomInventoryModel =
        DailyRoomInventoryModel(ROOM_TYPE_ID, date, totalRooms = total, reservedRooms = reserved)

    fun rate(date: LocalDate, price: Long): DailyRoomRateModel =
        DailyRoomRateModel(ROOM_TYPE_ID, date, Money.of(price))

    fun reservation(guestCount: Int = 2): ReservationModel =
        ReservationModel.create(
            userId = USER,
            property = property(),
            roomType = roomType(),
            period = period(),
            guestCount = guestCount,
            guest = GUEST,
            priceBeforeDiscount = Money.of(220_000),
            discountAmount = Money.ZERO,
            totalPrice = Money.of(220_000),
            couponId = null,
        )
}
