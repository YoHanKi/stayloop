package com.stayloop.domain.reservation.value

import com.stayloop.domain.property.RoomTypeModel
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * 예약 시점의 객실 타입 정보 박제(id·이름·인원). 별도 컬럼 중복 매핑을 피하기 위해 roomTypeId 도 여기 둔다.
 */
@Embeddable
data class RoomTypeSnapshot(
    @Column(name = "room_type_id", nullable = false)
    val roomTypeId: Long,
    @Column(name = "room_type_name", nullable = false, length = 100)
    val name: String,
    @Column(name = "room_type_base_guests", nullable = false)
    val baseGuests: Int,
    @Column(name = "room_type_max_guests", nullable = false)
    val maxGuests: Int,
) {
    /** 박제된 최대 인원으로 요청 인원을 검증한다. 0 명 이하·초과 모두 BAD_REQUEST. */
    fun checkGuestCount(requested: Int) {
        if (requested !in 1..maxGuests) {
            throw CoreException(ErrorType.BAD_REQUEST, "요청 인원($requested)이 객실 수용 범위(1~$maxGuests)를 벗어났습니다.")
        }
    }

    companion object {
        fun from(roomType: RoomTypeModel): RoomTypeSnapshot =
            RoomTypeSnapshot(
                roomTypeId = roomType.id,
                name = roomType.name,
                baseGuests = roomType.guestCount.baseGuests,
                maxGuests = roomType.guestCount.maxGuests,
            )
    }
}
