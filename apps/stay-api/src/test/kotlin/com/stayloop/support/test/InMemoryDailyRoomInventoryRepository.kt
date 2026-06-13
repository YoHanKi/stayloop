package com.stayloop.support.test

import com.stayloop.domain.inventory.DailyRoomInventoryId
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.inventory.DailyRoomInventoryRepository
import java.time.LocalDate

/**
 * 운영 어댑터와 동치 의미론의 테스트 더블 — 반-닫힌 구간 `[from, to)` 조회 + 자연 키 upsert.
 */
class InMemoryDailyRoomInventoryRepository : DailyRoomInventoryRepository {
    private val store = LinkedHashMap<DailyRoomInventoryId, DailyRoomInventoryModel>()

    override fun findById(roomTypeId: Long, date: LocalDate): DailyRoomInventoryModel? =
        store[DailyRoomInventoryId(roomTypeId, date)]

    override fun findAllInRange(roomTypeId: Long, from: LocalDate, to: LocalDate): List<DailyRoomInventoryModel> =
        store.values
            .filter { it.roomTypeId == roomTypeId && !it.date.isBefore(from) && it.date.isBefore(to) }
            .sortedBy { it.date }

    override fun save(inventory: DailyRoomInventoryModel): DailyRoomInventoryModel {
        store[DailyRoomInventoryId(inventory.roomTypeId, inventory.date)] = inventory
        return inventory
    }

    override fun saveAll(inventories: List<DailyRoomInventoryModel>): List<DailyRoomInventoryModel> =
        inventories.map { save(it) }
}
