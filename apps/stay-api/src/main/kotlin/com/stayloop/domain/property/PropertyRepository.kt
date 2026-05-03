package com.stayloop.domain.property

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface PropertyRepository {
    fun save(property: PropertyModel): PropertyModel

    fun findById(id: Long): PropertyModel?

    fun findByCity(city: String, pageable: Pageable): Page<PropertyModel>

    fun findAllByIds(ids: Collection<Long>): List<PropertyModel>

    fun deleteById(id: Long)
}
