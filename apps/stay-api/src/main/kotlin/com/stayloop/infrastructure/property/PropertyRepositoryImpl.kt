package com.stayloop.infrastructure.property

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.common.value.PageResult
import com.stayloop.domain.common.value.SortDirection
import com.stayloop.domain.common.value.SortKey
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.PropertyRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component

@Component
class PropertyRepositoryImpl(
    private val propertyJpaRepository: PropertyJpaRepository,
) : PropertyRepository {
    override fun save(property: PropertyModel): PropertyModel = propertyJpaRepository.save(property)

    override fun findById(id: Long): PropertyModel? = propertyJpaRepository.findById(id).orElse(null)

    override fun findByCity(city: String, query: PageQuery): PageResult<PropertyModel> {
        val pageable = PageRequest.of(query.offset / query.limit, query.limit, toSpringSort(query.sort))
        val page = propertyJpaRepository.findByCity(city, pageable)
        return PageResult(content = page.content, total = page.totalElements)
    }

    override fun findAllByIds(ids: Collection<Long>): List<PropertyModel> =
        if (ids.isEmpty()) emptyList() else propertyJpaRepository.findAllById(ids).toList()

    override fun deleteById(id: Long) = propertyJpaRepository.deleteById(id)

    private fun toSpringSort(keys: List<SortKey>): Sort {
        if (keys.isEmpty()) return Sort.unsorted()
        val orders = keys.map { key ->
            val column = DOMAIN_TO_COLUMN[key.property] ?: key.property
            when (key.direction) {
                SortDirection.ASC -> Sort.Order.asc(column)
                SortDirection.DESC -> Sort.Order.desc(column)
            }
        }
        return Sort.by(orders)
    }

    companion object {
        // 도메인 어휘 ↔ JPA 프로퍼티 경로 매핑 (도메인은 컬럼명을 모름)
        private val DOMAIN_TO_COLUMN: Map<String, String> = mapOf(
            "wishCount" to "wishCount",
            "rating" to "rating",
            "name" to "name.value",
        )
    }
}
