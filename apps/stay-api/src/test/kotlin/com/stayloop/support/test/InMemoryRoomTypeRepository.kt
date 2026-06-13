package com.stayloop.support.test

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.property.RoomTypeModel
import com.stayloop.domain.property.RoomTypeRepository

class InMemoryRoomTypeRepository : RoomTypeRepository {
    private val store = LinkedHashMap<Long, RoomTypeModel>()
    private var sequence = 0L

    override fun save(roomType: RoomTypeModel): RoomTypeModel {
        if (roomType.id == 0L) {
            assignId(roomType, ++sequence)
        }
        store[roomType.id] = roomType
        return roomType
    }

    override fun saveAll(roomTypes: List<RoomTypeModel>): List<RoomTypeModel> = roomTypes.map { save(it) }

    override fun findById(id: Long): RoomTypeModel? = store[id]

    override fun findByPropertyId(propertyId: Long): List<RoomTypeModel> =
        store.values.filter { it.propertyId == propertyId }

    private fun assignId(entity: BaseEntity, id: Long) {
        val field = BaseEntity::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.setLong(entity, id)
    }
}
