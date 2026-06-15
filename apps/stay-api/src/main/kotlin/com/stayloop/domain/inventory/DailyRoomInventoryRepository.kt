package com.stayloop.domain.inventory

import java.time.LocalDate

interface DailyRoomInventoryRepository {
    fun findById(roomTypeId: Long, date: LocalDate): DailyRoomInventoryModel?

    /** `[from, to)` 반-닫힌 구간(체크아웃 당일 제외)의 재고를 날짜 오름차순으로 조회한다. */
    fun findAllInRange(roomTypeId: Long, from: LocalDate, to: LocalDate): List<DailyRoomInventoryModel>

    /**
     * [dates] 중 가용한(`reserved + 1 <= total`) 일자만 원자적으로 1 차감하고 **실제 차감된 일자 수**를 돌려준다.
     * 반환값은 사실(fact)일 뿐 — "요청 일수와 다르면 품절" 같은 해석은 호출하는 도메인 서비스가 한다.
     */
    fun deductIfAvailable(roomTypeId: Long, dates: List<LocalDate>): Int

    /** [dates] 중 예약분이 있는(`reserved - 1 >= 0`) 일자만 원자적으로 1 복원하고 **실제 복원된 일자 수**를 돌려준다. */
    fun restore(roomTypeId: Long, dates: List<LocalDate>): Int

    fun save(inventory: DailyRoomInventoryModel): DailyRoomInventoryModel

    fun saveAll(inventories: List<DailyRoomInventoryModel>): List<DailyRoomInventoryModel>
}
