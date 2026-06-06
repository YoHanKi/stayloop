package com.stayloop.domain.property

interface PropertyRepository {
    fun save(property: PropertyModel): PropertyModel

    fun findById(id: Long): PropertyModel?

    fun findByCity(city: String, page: Int, size: Int): List<PropertyModel>

    fun countByCity(city: String): Long
}
