package com.stayloop.infrastructure.reservation

import com.stayloop.domain.reservation.ReservationModel
import org.springframework.data.jpa.repository.JpaRepository

/** CRUD 전용. 커스텀 조회 쿼리는 QueryDSL(RepositoryImpl)로 둔다. */
interface ReservationJpaRepository : JpaRepository<ReservationModel, Long>
