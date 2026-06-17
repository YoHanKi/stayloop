package com.stayloop.infrastructure.inventory

import com.stayloop.domain.inventory.DailyRoomInventoryId
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import org.springframework.data.jpa.repository.JpaRepository

/** CRUD 전용. 커스텀 조회·차감 쿼리는 QueryDSL(RepositoryImpl / Reserver)로 둔다. */
interface DailyRoomInventoryJpaRepository : JpaRepository<DailyRoomInventoryModel, DailyRoomInventoryId>
