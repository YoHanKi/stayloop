package com.stayloop.infrastructure.property

import com.querydsl.jpa.impl.JPAQueryFactory
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.PropertyRepository
import com.stayloop.domain.property.QPropertyModel
import org.springframework.stereotype.Component

@Component
class PropertyRepositoryImpl(
    private val propertyJpaRepository: PropertyJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : PropertyRepository {
    private val property = QPropertyModel.propertyModel

    override fun save(property: PropertyModel): PropertyModel = propertyJpaRepository.save(property)

    override fun findById(id: Long): PropertyModel? = propertyJpaRepository.findById(id).orElse(null)

    override fun findAllByIds(ids: List<Long>): List<PropertyModel> = propertyJpaRepository.findAllById(ids).toList()

    override fun findByCity(city: String, page: Int, size: Int): List<PropertyModel> =
        queryFactory
            .selectFrom(property)
            .where(property.address.city.eq(city))
            .offset(page.toLong() * size)
            .limit(size.toLong())
            .fetch()

    override fun countByCity(city: String): Long =
        queryFactory
            .select(property.count())
            .from(property)
            .where(property.address.city.eq(city))
            .fetchOne() ?: 0L

    override fun incrementWishCount(propertyId: Long): Int =
        queryFactory
            .update(property)
            .set(property.wishCount, property.wishCount.add(1))
            .where(property.id.eq(propertyId))
            .execute()
            .toInt()

    override fun decrementWishCount(propertyId: Long): Int =
        queryFactory
            .update(property)
            .set(property.wishCount, property.wishCount.subtract(1))
            .where(property.id.eq(propertyId), property.wishCount.gt(0))
            .execute()
            .toInt()

    override fun findWishCount(propertyId: Long): Int? =
        queryFactory
            .select(property.wishCount)
            .from(property)
            .where(property.id.eq(propertyId))
            .fetchOne()
}
