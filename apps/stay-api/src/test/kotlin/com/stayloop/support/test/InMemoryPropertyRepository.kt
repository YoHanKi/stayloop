package com.stayloop.support.test

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.PropertyRepository

/**
 * 운영 [com.stayloop.infrastructure.property.PropertyRepositoryImpl] 과 동치 의미론의 테스트 더블.
 * `@GeneratedValue` 를 흉내내기 위해 [BaseEntity.id] 를 reflection 으로 대입한다(1주차 패턴).
 */
class InMemoryPropertyRepository : PropertyRepository {
    private val store = LinkedHashMap<Long, PropertyModel>()
    private var sequence = 0L

    override fun save(property: PropertyModel): PropertyModel {
        if (property.id == 0L) {
            assignId(property, ++sequence)
        }
        store[property.id] = property
        return property
    }

    override fun findById(id: Long): PropertyModel? = store[id]

    override fun findAllByIds(ids: List<Long>): List<PropertyModel> = ids.mapNotNull { store[it] }

    override fun findByCity(city: String, page: Int, size: Int): List<PropertyModel> =
        store.values
            .filter { it.address.city == city }
            .drop(page * size)
            .take(size)

    override fun countByCity(city: String): Long =
        store.values.count { it.address.city == city }.toLong()

    override fun incrementWishCount(propertyId: Long): Int {
        val property = store[propertyId] ?: return 0
        property.incrementWishCount()
        return 1
    }

    override fun decrementWishCount(propertyId: Long): Int {
        val property = store[propertyId] ?: return 0
        if (property.wishCount <= 0) return 0
        property.decrementWishCount()
        return 1
    }

    override fun findWishCount(propertyId: Long): Int? = store[propertyId]?.wishCount

    private fun assignId(entity: BaseEntity, id: Long) {
        val field = BaseEntity::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.setLong(entity, id)
    }
}
