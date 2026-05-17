package com.stayloop.support.test

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.property.PropertyImageModel
import com.stayloop.domain.property.PropertyImageRepository

class InMemoryPropertyImageRepository : PropertyImageRepository {
    private val store = mutableMapOf<Long, PropertyImageModel>()
    private var sequence = 0L

    override fun save(image: PropertyImageModel): PropertyImageModel {
        if (image.id == 0L) {
            assignId(image, ++sequence)
        }
        store[image.id] = image
        return image
    }

    override fun deleteById(id: Long) {
        store.remove(id)
    }

    /**
     * 운영 Impl 의 `findByPropertyId` 와 *의미론 동치* — `displayOrder ASC, id ASC` 안정 정렬.
     */
    override fun findByPropertyId(propertyId: Long): List<PropertyImageModel> =
        store.values
            .filter { it.propertyId == propertyId }
            .sortedWith(compareBy({ it.displayOrder }, { it.id }))

    private fun assignId(image: PropertyImageModel, id: Long) {
        val field = BaseEntity::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.setLong(image, id)
    }
}
