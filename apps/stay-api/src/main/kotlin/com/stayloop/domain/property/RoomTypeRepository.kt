package com.stayloop.domain.property

interface RoomTypeRepository {
    fun save(roomType: RoomTypeModel): RoomTypeModel

    fun saveAll(roomTypes: List<RoomTypeModel>): List<RoomTypeModel>

    fun findById(id: Long): RoomTypeModel?

    fun findByPropertyId(propertyId: Long): List<RoomTypeModel>
}
