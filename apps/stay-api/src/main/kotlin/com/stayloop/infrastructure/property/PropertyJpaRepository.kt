package com.stayloop.infrastructure.property

import com.stayloop.domain.property.PropertyModel
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PropertyJpaRepository : JpaRepository<PropertyModel, Long> {
    // address.city 는 Address VO 의 @Embedded 필드 — 메서드 명명 underscore 회피 겸 매핑을 명시한다.
    @Query("SELECT p FROM PropertyModel p WHERE p.address.city = :city")
    fun findByCity(
        @Param("city") city: String,
        pageable: Pageable,
    ): List<PropertyModel>

    @Query("SELECT COUNT(p) FROM PropertyModel p WHERE p.address.city = :city")
    fun countByCity(
        @Param("city") city: String,
    ): Long
}
