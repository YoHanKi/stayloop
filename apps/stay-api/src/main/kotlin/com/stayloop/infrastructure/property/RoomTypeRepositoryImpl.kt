package com.stayloop.infrastructure.property

import com.stayloop.domain.property.RoomTypeModel
import com.stayloop.domain.property.RoomTypeRepository
import org.springframework.stereotype.Component

@Component
class RoomTypeRepositoryImpl(
    private val roomTypeJpaRepository: RoomTypeJpaRepository,
) : RoomTypeRepository {
    override fun save(roomType: RoomTypeModel): RoomTypeModel = roomTypeJpaRepository.save(roomType)

    override fun saveAll(roomTypes: List<RoomTypeModel>): List<RoomTypeModel> = roomTypeJpaRepository.saveAll(roomTypes)

    override fun findById(id: Long): RoomTypeModel? = roomTypeJpaRepository.findById(id).orElse(null)

    override fun findByPropertyId(propertyId: Long): List<RoomTypeModel> = roomTypeJpaRepository.findByPropertyId(propertyId)
}
