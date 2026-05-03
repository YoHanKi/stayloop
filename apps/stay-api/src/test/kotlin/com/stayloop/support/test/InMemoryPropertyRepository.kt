package com.stayloop.support.test

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.PropertyRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable

class InMemoryPropertyRepository : PropertyRepository {
    private val store = mutableMapOf<Long, PropertyModel>()
    private var sequence = 0L

    override fun save(property: PropertyModel): PropertyModel {
        if (property.id == 0L) {
            assignId(property, ++sequence)
        }
        store[property.id] = property
        return property
    }

    override fun findById(id: Long): PropertyModel? = store[id]

    override fun findByCity(city: String, pageable: Pageable): Page<PropertyModel> {
        val matched = store.values.filter { it.address.city == city }
        val from = pageable.offset.toInt().coerceAtMost(matched.size)
        val to = (from + pageable.pageSize).coerceAtMost(matched.size)
        return PageImpl(matched.subList(from, to), pageable, matched.size.toLong())
    }

    override fun findAllByIds(ids: Collection<Long>): List<PropertyModel> =
        ids.mapNotNull { store[it] }

    override fun deleteById(id: Long) {
        store.remove(id)
    }

    private fun assignId(property: PropertyModel, id: Long) {
        val field = BaseEntity::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.setLong(property, id)
    }
}
