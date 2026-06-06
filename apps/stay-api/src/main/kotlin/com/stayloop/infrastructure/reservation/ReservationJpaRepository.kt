package com.stayloop.infrastructure.reservation

import com.stayloop.domain.reservation.ReservationModel
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ReservationJpaRepository : JpaRepository<ReservationModel, Long> {
    @Query(
        "SELECT r FROM ReservationModel r WHERE r.userId.value = :loginId " +
            "ORDER BY r.period.checkIn DESC, r.id DESC",
    )
    fun findByUserLoginId(
        @Param("loginId") loginId: String,
        pageable: Pageable,
    ): List<ReservationModel>
}
