package com.stayloop.support.test

import com.stayloop.domain.inventory.DailyRoomInventoryId
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.inventory.DailyRoomInventoryRepository
import java.time.LocalDate

/**
 * 테스트용 InMemory `DailyRoomInventoryRepository`. 운영 RepositoryImpl 의 의미론과 동치 —
 * **반-닫힌 구간 `[from, to)`** / **자연키 일관성** / **`saveAll` 단건 upsert** 모두 운영과 같게 동작
 * (verify-code §19-A 운영-테스트 동치성).
 */
class InMemoryDailyRoomInventoryRepository : DailyRoomInventoryRepository {
    private val store = mutableMapOf<DailyRoomInventoryId, DailyRoomInventoryModel>()

    override fun findById(roomTypeId: Long, date: LocalDate): DailyRoomInventoryModel? =
        store[DailyRoomInventoryId(roomTypeId, date)]

    override fun findAllInRange(
        roomTypeId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<DailyRoomInventoryModel> =
        store.values
            .filter { it.roomTypeId == roomTypeId && !it.date.isBefore(from) && it.date.isBefore(to) }
            .sortedBy { it.date }

    override fun saveAll(inventories: Collection<DailyRoomInventoryModel>): List<DailyRoomInventoryModel> =
        inventories.map { save(it) }

    override fun save(inventory: DailyRoomInventoryModel): DailyRoomInventoryModel {
        store[DailyRoomInventoryId(inventory.roomTypeId, inventory.date)] = inventory
        return inventory
    }
}
