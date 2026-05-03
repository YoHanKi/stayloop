package com.stayloop.domain.property

interface RoomTypeRepository {
    fun save(roomType: RoomTypeModel): RoomTypeModel

    fun findById(id: Long): RoomTypeModel?

    fun findByPropertyId(propertyId: Long): List<RoomTypeModel>

    fun findAllByPropertyIds(propertyIds: Collection<Long>): List<RoomTypeModel>

    fun deleteById(id: Long)
}
