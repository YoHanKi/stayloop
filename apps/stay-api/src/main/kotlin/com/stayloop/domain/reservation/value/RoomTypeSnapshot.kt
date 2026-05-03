package com.stayloop.domain.reservation.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * 예약 시점의 RoomType 박제. (`docs/design/03-class-diagram.md §4`, `docs/plan/week2-3.md §⑦`)
 *
 * 박제 의도와 단순화 정책은 `PropertySnapshot` 과 동일 — 원본 VO(`Name` / `GuestCount`) 를 그대로 임베드하지
 * 않고 *이미 직렬화된 의미값* 만 박제한다.
 *
 * 박제 항목:
 * - `roomTypeId` (FK 참조용)
 * - `roomTypeName` — RoomType.name.value 박제
 * - `maxGuests` — `GuestCount.max` 박제. 인원 검증 (예약자 수 ≤ maxGuests) 이 예약 시점 기준으로 동결
 *
 * 도메인 가드:
 * - `roomTypeId > 0`
 * - `roomTypeName` 비공백, 1~`MAX_NAME_LENGTH(100)` 자
 * - `maxGuests > 0`
 */
@Embeddable
data class RoomTypeSnapshot(
    @Column(name = "room_type_id", nullable = false)
    val roomTypeId: Long,
    @Column(name = "room_type_name", nullable = false, length = MAX_NAME_LENGTH)
    val roomTypeName: String,
    @Column(name = "max_guests", nullable = false)
    val maxGuests: Int,
) {
    init {
        if (roomTypeId <= 0L) {
            throw CoreException(ErrorType.BAD_REQUEST, "roomTypeId 는 양수여야 합니다 (영속화된 RoomType 의 id).")
        }
        if (roomTypeName.isBlank() || roomTypeName.length > MAX_NAME_LENGTH) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "박제 roomTypeName 은 1~${MAX_NAME_LENGTH}자의 비공백 문자열이어야 합니다.",
            )
        }
        if (maxGuests <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "박제 maxGuests 는 양수여야 합니다.")
        }
    }

    companion object {
        const val MAX_NAME_LENGTH: Int = 100
    }
}
