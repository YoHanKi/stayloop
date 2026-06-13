package com.stayloop.infrastructure.property

import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.PropertyRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component

@Component
class PropertyRepositoryImpl(
    private val propertyJpaRepository: PropertyJpaRepository,
) : PropertyRepository {
    override fun save(property: PropertyModel): PropertyModel = propertyJpaRepository.save(property)

    override fun findById(id: Long): PropertyModel? = propertyJpaRepository.findById(id).orElse(null)

    override fun findAllByIds(ids: List<Long>): List<PropertyModel> = propertyJpaRepository.findAllById(ids).toList()

    override fun findByCity(city: String, page: Int, size: Int): List<PropertyModel> =
        propertyJpaRepository.findByCity(city, PageRequest.of(page, size))

    override fun countByCity(city: String): Long = propertyJpaRepository.countByCity(city)
}
