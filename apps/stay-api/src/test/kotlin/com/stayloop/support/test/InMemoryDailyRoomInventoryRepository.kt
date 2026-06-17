package com.stayloop.support.test

import com.stayloop.domain.inventory.DailyRoomInventoryId
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.inventory.DailyRoomInventoryRepository
import java.time.LocalDate

/**
 * 운영 어댑터와 동치 의미론의 테스트 더블 — 반-닫힌 구간 `[from, to)` 조회 + 자연 키 upsert +
 * 조건부 원자 차감/복원(차감된 일자 수 반환).
 *
 * 운영의 조건부 UPDATE 는 가용 행만 갱신하고 트랜잭션 롤백으로 부분 차감을 되돌린다. POJO 테스트엔
 * 트랜잭션이 없으므로, 전 일자가 가용할 때만 실제로 차감하고 그 외엔 가용 일자 수만 돌려준다(변이 없음) —
 * 도메인 서비스의 all-or-nothing 판단에 대해 운영과 같은 관측 결과를 준다.
 */
class InMemoryDailyRoomInventoryRepository : DailyRoomInventoryRepository {
    private val store = LinkedHashMap<DailyRoomInventoryId, DailyRoomInventoryModel>()

    override fun findById(roomTypeId: Long, date: LocalDate): DailyRoomInventoryModel? =
        store[DailyRoomInventoryId(roomTypeId, date)]

    override fun findAllInRange(roomTypeId: Long, from: LocalDate, to: LocalDate): List<DailyRoomInventoryModel> =
        store.values
            .filter { it.roomTypeId == roomTypeId && !it.date.isBefore(from) && it.date.isBefore(to) }
            .sortedBy { it.date }

    override fun deductIfAvailable(roomTypeId: Long, dates: List<LocalDate>): Int {
        val target = dates.distinct()
        val deductable = target.mapNotNull { findById(roomTypeId, it) }.filter { it.available() > 0 }
        if (deductable.size != target.size) return deductable.size // 일부 매진/부재 → 변이 없이 가용 수만 보고
        deductable.forEach { it.reserveOne() }
        saveAll(deductable)
        return target.size
    }

    override fun restore(roomTypeId: Long, dates: List<LocalDate>): Int {
        val target = dates.distinct()
        val restorable = target.mapNotNull { findById(roomTypeId, it) }.filter { it.reservedRooms > 0 }
        if (restorable.size != target.size) return restorable.size
        restorable.forEach { it.releaseOne() }
        saveAll(restorable)
        return target.size
    }

    override fun save(inventory: DailyRoomInventoryModel): DailyRoomInventoryModel {
        store[DailyRoomInventoryId(inventory.roomTypeId, inventory.date)] = inventory
        return inventory
    }

    override fun saveAll(inventories: List<DailyRoomInventoryModel>): List<DailyRoomInventoryModel> =
        inventories.map { save(it) }
}
