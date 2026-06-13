package com.stayloop.domain.inventory

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.Check
import java.time.LocalDate

/**
 * 일자별 객실 재고. 재고는 객실 타입이 아니라 날짜에 매달리므로 `(roomTypeId, date)` 자연 키로 둔다(04 §2.1).
 *
 * 음수 재고는 이중으로 막는다 — 도메인 1차 가드([reserveOne] / [releaseOne] 의 `require`) +
 * DB 최후 방어선(CHECK 제약). 동시 차감(더블부킹) 안전성은 4주차 락 영역의 알려진 공백이다.
 */
@Entity
@Table(name = "daily_room_inventories")
@IdClass(DailyRoomInventoryId::class)
@Check(constraints = "reserved_rooms >= 0 AND reserved_rooms <= total_rooms")
class DailyRoomInventoryModel(
    roomTypeId: Long,
    date: LocalDate,
    totalRooms: Int,
    reservedRooms: Int = 0,
) {
    @Id
    @Column(name = "room_type_id", nullable = false)
    val roomTypeId: Long = roomTypeId

    @Id
    @Column(name = "date", nullable = false)
    val date: LocalDate = date

    @Column(name = "total_rooms", nullable = false)
    var totalRooms: Int = totalRooms
        protected set

    @Column(name = "reserved_rooms", nullable = false)
    var reservedRooms: Int = reservedRooms
        protected set

    init {
        if (totalRooms < 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "총 객실 수는 음수일 수 없습니다.")
        }
        if (reservedRooms < 0 || reservedRooms > totalRooms) {
            throw CoreException(ErrorType.BAD_REQUEST, "예약 객실 수는 0 이상 총 객실 수 이하여야 합니다.")
        }
    }

    /** 남은 객실 수. */
    fun available(): Int = totalRooms - reservedRooms

    /** 한 객실을 차감한다. 남은 객실이 없으면 CONFLICT. */
    fun reserveOne() {
        if (available() <= 0) {
            throw CoreException(ErrorType.CONFLICT, "해당 날짜($date)에 예약 가능한 객실이 없습니다.")
        }
        reservedRooms += 1
    }

    /** 한 객실을 복원한다. 예약된 객실이 없으면 BAD_REQUEST(음수 복원 방지). */
    fun releaseOne() {
        if (reservedRooms <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "복원할 예약 객실이 없습니다.")
        }
        reservedRooms -= 1
    }
}
