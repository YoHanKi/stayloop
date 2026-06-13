package com.stayloop.infrastructure.inventory

import com.stayloop.domain.inventory.DailyRoomInventoryId
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.inventory.DailyRoomInventoryRepository
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class DailyRoomInventoryRepositoryImpl(
    private val jpaRepository: DailyRoomInventoryJpaRepository,
) : DailyRoomInventoryRepository {
    override fun findById(roomTypeId: Long, date: LocalDate): DailyRoomInventoryModel? =
        jpaRepository.findById(DailyRoomInventoryId(roomTypeId, date)).orElse(null)

    override fun findAllInRange(roomTypeId: Long, from: LocalDate, to: LocalDate): List<DailyRoomInventoryModel> =
        jpaRepository.findInRange(roomTypeId, from, to)

    override fun save(inventory: DailyRoomInventoryModel): DailyRoomInventoryModel = jpaRepository.save(inventory)

    override fun saveAll(inventories: List<DailyRoomInventoryModel>): List<DailyRoomInventoryModel> =
        jpaRepository.saveAll(inventories)
}
