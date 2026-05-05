package com.stayloop.application.property

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.property.RoomTypeModel
import com.stayloop.domain.property.value.BedType

/**
 * 객실 타입의 *기간 + 인원 조합* 가용성. (`docs/design/02-sequence-diagram.md §1`)
 *
 * `available = false` 인 항목도 응답에 포함되며 `totalPrice` / `pricePerNight` 는 null.
 * 사용자가 "왜 이 객실은 안 되는가" (인원 초과 / 재고 0 / 일자 누락) 을 UI 에서 구분할 수 있도록
 * `unavailableReason` 도 함께 노출.
 */
data class RoomTypeAvailabilityInfo(
    val roomTypeId: Long,
    val name: String,
    val baseGuests: Int,
    val maxGuests: Int,
    val beds: Map<BedType, Int>,
    val available: Boolean,
    val totalPrice: Money?,
    val pricePerNight: Money?,
    val unavailableReason: String?,
) {
    companion object {
        fun unavailable(roomType: RoomTypeModel, reason: String): RoomTypeAvailabilityInfo =
            RoomTypeAvailabilityInfo(
                roomTypeId = roomType.id,
                name = roomType.name.value,
                baseGuests = roomType.guestCount.base,
                maxGuests = roomType.guestCount.max,
                beds = roomType.bedConfig.beds,
                available = false,
                totalPrice = null,
                pricePerNight = null,
                unavailableReason = reason,
            )

        fun available(roomType: RoomTypeModel, totalPrice: Money, nights: Int): RoomTypeAvailabilityInfo {
            val perNight = if (nights > 0) Money.of(totalPrice.amount / nights) else Money.ZERO
            return RoomTypeAvailabilityInfo(
                roomTypeId = roomType.id,
                name = roomType.name.value,
                baseGuests = roomType.guestCount.base,
                maxGuests = roomType.guestCount.max,
                beds = roomType.bedConfig.beds,
                available = true,
                totalPrice = totalPrice,
                pricePerNight = perNight,
                unavailableReason = null,
            )
        }
    }
}
