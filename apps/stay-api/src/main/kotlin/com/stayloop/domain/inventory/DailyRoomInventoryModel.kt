package com.stayloop.domain.inventory

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDate

/**
 * 일자별 객실 재고. (`docs/design/03-class-diagram.md §2`, `04-erd.md §2.1`)
 * **자연 키** `(roomTypeId, date)` — 같은 객실 타입의 같은 일자가 두 행이 될 수 없다.
 *
 * `reservedRooms` 는 `private` 노출 — 외부는 `available()` / `reserveOne()` / `releaseOne()` 으로만 접근.
 * 카운트 필드를 직접 mutable 로 노출하면 음수/초과 진입의 우회 경로가 열린다 (`docs/plan/week2-3.md` 결정).
 *
 * 도메인 레벨 가드:
 * - 생성 시 `0 <= reserved <= total`
 * - `reserveOne()` 은 `available() > 0` 일 때만 (full 시 CONFLICT)
 * - `releaseOne()` 은 `reserved > 0` 일 때만 (음수 진입 차단)
 *
 * DB CHECK 제약(`total_rooms > 0`, `reserved_rooms BETWEEN 0 AND total`) 은 **마지막 방어선** — 도메인이 1차.
 */
@Entity
@Table(
    name = "daily_room_inventories",
    indexes = [Index(name = "idx_inventory_room_type_date", columnList = "room_type_id, date")],
)
@IdClass(DailyRoomInventoryId::class)
class DailyRoomInventoryModel internal constructor(
    roomTypeId: Long,
    date: LocalDate,
    totalRooms: Int,
    reservedRooms: Int = 0,
) {

    @Id
    @Column(name = "room_type_id", nullable = false)
    var roomTypeId: Long = roomTypeId
        protected set

    @Id
    @Column(name = "date", nullable = false)
    var date: LocalDate = date
        protected set

    @Column(name = "total_rooms", nullable = false)
    var totalRooms: Int = totalRooms
        protected set

    @Column(name = "reserved_rooms", nullable = false)
    private var reservedRooms: Int = reservedRooms

    init {
        if (roomTypeId <= 0L) {
            throw CoreException(ErrorType.BAD_REQUEST, "roomTypeId 는 양수여야 합니다 (영속화된 RoomType 의 id).")
        }
        if (totalRooms <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "totalRooms 는 1 이상이어야 합니다.")
        }
        if (reservedRooms < 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "reservedRooms 는 음수일 수 없습니다.")
        }
        if (reservedRooms > totalRooms) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "reservedRooms($reservedRooms) 는 totalRooms($totalRooms) 를 초과할 수 없습니다.",
            )
        }
    }

    /**
     * 현재 가용 객실 수. **음수가 될 수 없음** — `init` / `reserveOne` / `releaseOne` 가드의 결과.
     */
    fun available(): Int = totalRooms - reservedRooms

    /**
     * 한 객실 차감. 가용 0 일 때 호출되면 CONFLICT 로 거절 — 더블부킹 방지의 도메인 1차 가드.
     * 동시 차감 락은 4주차 영역 (`docs/design/05 §3`).
     */
    fun reserveOne() {
        if (available() <= 0) {
            throw CoreException(
                ErrorType.CONFLICT,
                "객실 재고가 부족합니다 (roomTypeId=$roomTypeId, date=$date).",
            )
        }
        reservedRooms += 1
    }

    /**
     * 한 객실 복원 (예약 취소 흐름). `reservedRooms == 0` 인 상태에서 호출되면 CONFLICT —
     * 멱등성 깨짐을 도메인이 가시화 (Facade 가 멱등을 책임지든 호출 측에서 잘못된 흐름이든 즉시 노출).
     */
    fun releaseOne() {
        if (reservedRooms <= 0) {
            throw CoreException(
                ErrorType.CONFLICT,
                "복원할 예약 객실이 없습니다 (roomTypeId=$roomTypeId, date=$date).",
            )
        }
        reservedRooms -= 1
    }

    /**
     * 외부 read-only 노출 — 검색/표시용. 변경은 `reserveOne` / `releaseOne` 으로만.
     */
    fun reserved(): Int = reservedRooms

    companion object {
        fun create(
            roomTypeId: Long,
            date: LocalDate,
            totalRooms: Int,
            reservedRooms: Int = 0,
        ): DailyRoomInventoryModel = DailyRoomInventoryModel(
            roomTypeId = roomTypeId,
            date = date,
            totalRooms = totalRooms,
            reservedRooms = reservedRooms,
        )
    }
}
