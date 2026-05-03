package com.stayloop.infrastructure.inventory

import com.stayloop.domain.inventory.DailyRoomInventoryId
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.inventory.DailyRoomInventoryRepository
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 도메인 `DailyRoomInventoryRepository` 의 인프라 어댑터. JpaRepository 위임만.
 *
 * 자연키 `(roomTypeId, date)` 의 단건 조회는 `JpaRepository.findById(DailyRoomInventoryId(...))` 로 변환한다 —
 * 도메인 인터페이스가 `(roomTypeId, date)` 두 인자를 받는 시그니처를 유지하기 위한 어댑터 책임.
 */
@Component
class DailyRoomInventoryRepositoryImpl(
    private val jpa: DailyRoomInventoryJpaRepository,
) : DailyRoomInventoryRepository {
    override fun findById(roomTypeId: Long, date: LocalDate): DailyRoomInventoryModel? =
        jpa.findById(DailyRoomInventoryId(roomTypeId, date)).orElse(null)

    override fun findAllInRange(
        roomTypeId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<DailyRoomInventoryModel> = jpa.findAllInRange(roomTypeId, from, to)

    override fun saveAll(inventories: Collection<DailyRoomInventoryModel>): List<DailyRoomInventoryModel> =
        jpa.saveAll(inventories).toList()

    override fun save(inventory: DailyRoomInventoryModel): DailyRoomInventoryModel = jpa.save(inventory)
}
