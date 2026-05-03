package com.stayloop.infrastructure.property

import com.stayloop.domain.property.RoomTypeModel
import com.stayloop.domain.property.RoomTypeRepository
import org.springframework.stereotype.Component

@Component
class RoomTypeRepositoryImpl(
    private val roomTypeJpaRepository: RoomTypeJpaRepository,
) : RoomTypeRepository {
    override fun save(roomType: RoomTypeModel): RoomTypeModel = roomTypeJpaRepository.save(roomType)

    override fun findById(id: Long): RoomTypeModel? = roomTypeJpaRepository.findById(id).orElse(null)

    override fun findByPropertyId(propertyId: Long): List<RoomTypeModel> =
        roomTypeJpaRepository.findByPropertyId(propertyId)

    override fun findAllByPropertyIds(propertyIds: Collection<Long>): List<RoomTypeModel> =
        if (propertyIds.isEmpty()) emptyList() else roomTypeJpaRepository.findAllByPropertyIdIn(propertyIds)

    override fun deleteById(id: Long) = roomTypeJpaRepository.deleteById(id)
}
