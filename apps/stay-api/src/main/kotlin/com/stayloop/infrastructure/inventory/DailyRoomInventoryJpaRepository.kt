package com.stayloop.infrastructure.inventory

import com.stayloop.domain.inventory.DailyRoomInventoryId
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface DailyRoomInventoryJpaRepository : JpaRepository<DailyRoomInventoryModel, DailyRoomInventoryId> {
    @Query(
        "SELECT i FROM DailyRoomInventoryModel i " +
            "WHERE i.roomTypeId = :roomTypeId AND i.date >= :from AND i.date < :to " +
            "ORDER BY i.date ASC",
    )
    fun findInRange(
        @Param("roomTypeId") roomTypeId: Long,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<DailyRoomInventoryModel>
}
