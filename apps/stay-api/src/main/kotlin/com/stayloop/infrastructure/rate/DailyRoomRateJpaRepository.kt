package com.stayloop.infrastructure.rate

import com.stayloop.domain.rate.DailyRoomRateId
import com.stayloop.domain.rate.DailyRoomRateModel
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface DailyRoomRateJpaRepository : JpaRepository<DailyRoomRateModel, DailyRoomRateId> {
    @Query(
        "SELECT r FROM DailyRoomRateModel r " +
            "WHERE r.roomTypeId = :roomTypeId AND r.date >= :from AND r.date < :to " +
            "ORDER BY r.date ASC",
    )
    fun findInRange(
        @Param("roomTypeId") roomTypeId: Long,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<DailyRoomRateModel>
}
