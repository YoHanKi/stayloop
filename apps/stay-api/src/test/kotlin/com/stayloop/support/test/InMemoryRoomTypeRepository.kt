package com.stayloop.support.test

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.property.RoomTypeModel
import com.stayloop.domain.property.RoomTypeRepository

class InMemoryRoomTypeRepository : RoomTypeRepository {
    private val store = mutableMapOf<Long, RoomTypeModel>()
    private var sequence = 0L

    override fun save(roomType: RoomTypeModel): RoomTypeModel {
        if (roomType.id == 0L) {
            assignId(roomType, ++sequence)
        }
        store[roomType.id] = roomType
        return roomType
    }

    override fun findById(id: Long): RoomTypeModel? = store[id]

    override fun findByPropertyId(propertyId: Long): List<RoomTypeModel> =
        store.values.filter { it.propertyId == propertyId }

    override fun findAllByPropertyIds(propertyIds: Collection<Long>): List<RoomTypeModel> =
        store.values.filter { it.propertyId in propertyIds }

    override fun deleteById(id: Long) {
        store.remove(id)
    }

    private fun assignId(roomType: RoomTypeModel, id: Long) {
        val field = BaseEntity::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.setLong(roomType, id)
    }
}
