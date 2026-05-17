package com.stayloop.infrastructure.property

import com.querydsl.jpa.impl.JPAQueryFactory
import com.stayloop.domain.property.PropertyImageModel
import com.stayloop.domain.property.PropertyImageRepository
import com.stayloop.domain.property.QPropertyImageModel
import org.springframework.stereotype.Component

@Component
class PropertyImageRepositoryImpl(
    private val propertyImageJpaRepository: PropertyImageJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : PropertyImageRepository {
    override fun save(image: PropertyImageModel): PropertyImageModel = propertyImageJpaRepository.save(image)

    override fun deleteById(id: Long) = propertyImageJpaRepository.deleteById(id)

    /**
     * `displayOrder ASC, id ASC` 안정 정렬. V001 의 `idx_property_images(property_id, display_order)` prefix
     * scan 정합 — `WHERE property_id = ?` equality + `ORDER BY display_order ASC` 가 인덱스 순서로 처리.
     */
    override fun findByPropertyId(propertyId: Long): List<PropertyImageModel> {
        val image = QPropertyImageModel.propertyImageModel
        return queryFactory
            .selectFrom(image)
            .where(image.propertyId.eq(propertyId))
            .orderBy(image.displayOrder.asc(), image.id.asc())
            .fetch()
    }
}
