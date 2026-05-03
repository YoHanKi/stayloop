package com.stayloop.infrastructure.property

import com.stayloop.domain.property.RoomTypeModel
import org.springframework.data.jpa.repository.JpaRepository

interface RoomTypeJpaRepository : JpaRepository<RoomTypeModel, Long> {
    fun findByPropertyId(propertyId: Long): List<RoomTypeModel>

    fun findAllByPropertyIdIn(propertyIds: Collection<Long>): List<RoomTypeModel>
}
