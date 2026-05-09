package com.stayloop.support.test

import com.stayloop.domain.inventory.DailyRoomInventoryId
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.inventory.DailyRoomInventoryRepository
import java.time.LocalDate

/**
 * 테스트용 InMemory `DailyRoomInventoryRepository`. 운영 RepositoryImpl 의 의미론과 동치 —
 * **반-닫힌 구간 `[from, to)`** / **자연키 일관성** / **`saveAll` 단건 upsert** / **`findInventoriesForUpdate`
 * `date ASC` 정렬** 모두 운영과 같게 동작 (verify-code §19-A 운영-테스트 동치성).
 *
 * **동시성 의미론 한계** — `synchronized` 만으로는 MySQL InnoDB 의 next-key gap lock / MVCC snapshot /
 * deadlock 감지를 재현할 수 없다 (`db-lock-low-level.md` LQ20, Phase 0 E-8). **동시성 시나리오는
 * Testcontainers + 실 MySQL 로 검증** 한다 (verify-code R9 — InMemory 동시성 시뮬레이션 금지). 본 더블의
 * `findInventoriesForUpdate` 는 *정렬 / 누락 행 처리 / 빈 입력* 등 *결정적 의미론* 만 운영과 동치.
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

    override fun findInventoriesForUpdate(
        roomTypeId: Long,
        dates: List<LocalDate>,
    ): List<DailyRoomInventoryModel> {
        if (dates.isEmpty()) return emptyList()
        val targets = dates.toSet()
        return store.values
            .filter { it.roomTypeId == roomTypeId && it.date in targets }
            .sortedBy { it.date }
    }

    override fun saveAll(inventories: Collection<DailyRoomInventoryModel>): List<DailyRoomInventoryModel> =
        inventories.map { save(it) }

    override fun save(inventory: DailyRoomInventoryModel): DailyRoomInventoryModel {
        store[DailyRoomInventoryId(inventory.roomTypeId, inventory.date)] = inventory
        return inventory
    }
}
