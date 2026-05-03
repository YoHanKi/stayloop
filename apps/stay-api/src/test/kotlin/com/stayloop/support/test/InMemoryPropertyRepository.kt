package com.stayloop.support.test

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.common.value.PageResult
import com.stayloop.domain.common.value.SortDirection
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.PropertyRepository
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType

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

    /**
     * 도메인 어휘 정렬: `wishCount`, `rating`, `name`. **그 외 키는 BAD_REQUEST 로 거절** —
     * 운영 RepositoryImpl 과 정책 일관 (Copilot #8 가드).
     */
    override fun findByCity(city: String, query: PageQuery): PageResult<PropertyModel> {
        val matched = store.values.filter { it.address.city == city }
        val sorted = applySort(matched, query)
        val from = query.offset.coerceAtMost(sorted.size)
        val to = (from + query.limit).coerceAtMost(sorted.size)
        return PageResult(content = sorted.subList(from, to), total = matched.size.toLong())
    }

    override fun findAllByIds(ids: Collection<Long>): List<PropertyModel> = ids.mapNotNull { store[it] }

    override fun deleteById(id: Long) {
        store.remove(id)
    }

    private fun applySort(list: List<PropertyModel>, query: PageQuery): List<PropertyModel> {
        if (query.sort.isEmpty()) return list
        val first = query.sort.first()
        val keySelector: (PropertyModel) -> Comparable<*>? = when (first.property) {
            "wishCount" -> { p -> p.wishCount }
            "rating" -> { p -> p.rating.value }
            "name" -> { p -> p.name.value }
            else -> throw CoreException(
                ErrorType.BAD_REQUEST,
                "지원하지 않는 정렬 키입니다: ${first.property}",
            )
        }

        @Suppress("UNCHECKED_CAST")
        val comparator: Comparator<PropertyModel> = compareBy { keySelector(it) as Comparable<Any>? }
        return when (first.direction) {
            SortDirection.ASC -> list.sortedWith(comparator)
            SortDirection.DESC -> list.sortedWith(comparator.reversed())
        }
    }

    private fun assignId(property: PropertyModel, id: Long) {
        val field = BaseEntity::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.setLong(property, id)
    }
}
