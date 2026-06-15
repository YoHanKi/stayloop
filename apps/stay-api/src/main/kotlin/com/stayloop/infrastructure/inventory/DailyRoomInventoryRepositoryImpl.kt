package com.stayloop.infrastructure.inventory

import com.querydsl.jpa.impl.JPAQueryFactory
import com.stayloop.domain.inventory.DailyRoomInventoryId
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.inventory.DailyRoomInventoryRepository
import com.stayloop.domain.inventory.QDailyRoomInventoryModel
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class DailyRoomInventoryRepositoryImpl(
    private val jpaRepository: DailyRoomInventoryJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : DailyRoomInventoryRepository {
    private val inventory = QDailyRoomInventoryModel.dailyRoomInventoryModel

    override fun findById(roomTypeId: Long, date: LocalDate): DailyRoomInventoryModel? =
        jpaRepository.findById(DailyRoomInventoryId(roomTypeId, date)).orElse(null)

    override fun findAllInRange(roomTypeId: Long, from: LocalDate, to: LocalDate): List<DailyRoomInventoryModel> =
        queryFactory
            .selectFrom(inventory)
            .where(
                inventory.roomTypeId.eq(roomTypeId),
                inventory.date.goe(from),
                inventory.date.lt(to),
            )
            .orderBy(inventory.date.asc())
            .fetch()

    override fun deductIfAvailable(roomTypeId: Long, dates: List<LocalDate>): Int =
        queryFactory
            .update(inventory)
            .set(inventory.reservedRooms, inventory.reservedRooms.add(1))
            .where(
                inventory.roomTypeId.eq(roomTypeId),
                inventory.date.`in`(dates),
                inventory.reservedRooms.add(1).loe(inventory.totalRooms),
            )
            .execute()
            .toInt()

    override fun restore(roomTypeId: Long, dates: List<LocalDate>): Int =
        queryFactory
            .update(inventory)
            .set(inventory.reservedRooms, inventory.reservedRooms.subtract(1))
            .where(
                inventory.roomTypeId.eq(roomTypeId),
                inventory.date.`in`(dates),
                inventory.reservedRooms.subtract(1).goe(0),
            )
            .execute()
            .toInt()

    override fun save(inventory: DailyRoomInventoryModel): DailyRoomInventoryModel = jpaRepository.save(inventory)

    override fun saveAll(inventories: List<DailyRoomInventoryModel>): List<DailyRoomInventoryModel> =
        jpaRepository.saveAll(inventories)
}
