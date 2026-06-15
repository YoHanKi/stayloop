package com.stayloop.infrastructure.rate

import com.stayloop.domain.rate.DailyRoomRateId
import com.stayloop.domain.rate.DailyRoomRateModel
import org.springframework.data.jpa.repository.JpaRepository

/** CRUD 전용. 커스텀 조회 쿼리는 QueryDSL(RepositoryImpl)로 둔다. */
interface DailyRoomRateJpaRepository : JpaRepository<DailyRoomRateModel, DailyRoomRateId>
