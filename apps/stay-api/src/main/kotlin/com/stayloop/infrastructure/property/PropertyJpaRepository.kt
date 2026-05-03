package com.stayloop.infrastructure.property

import com.stayloop.domain.property.PropertyModel
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PropertyJpaRepository : JpaRepository<PropertyModel, Long> {
    @Query("SELECT p FROM PropertyModel p WHERE p.address.city = :city")
    fun findByCity(@Param("city") city: String, pageable: Pageable): Page<PropertyModel>
}
