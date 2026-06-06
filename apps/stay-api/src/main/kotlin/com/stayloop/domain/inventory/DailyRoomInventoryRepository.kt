package com.stayloop.domain.inventory

import java.time.LocalDate

interface DailyRoomInventoryRepository {
    fun findById(roomTypeId: Long, date: LocalDate): DailyRoomInventoryModel?

    /** `[from, to)` 반-닫힌 구간(체크아웃 당일 제외)의 재고를 날짜 오름차순으로 조회한다. */
    fun findAllInRange(roomTypeId: Long, from: LocalDate, to: LocalDate): List<DailyRoomInventoryModel>

    fun save(inventory: DailyRoomInventoryModel): DailyRoomInventoryModel

    fun saveAll(inventories: List<DailyRoomInventoryModel>): List<DailyRoomInventoryModel>
}
