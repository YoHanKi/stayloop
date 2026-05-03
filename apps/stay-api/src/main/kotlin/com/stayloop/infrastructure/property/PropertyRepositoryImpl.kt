package com.stayloop.infrastructure.property

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.common.value.PageResult
import com.stayloop.domain.common.value.SortDirection
import com.stayloop.domain.common.value.SortKey
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.PropertyRepository
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
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
        val pageable = PageRequest.of(query.page, query.size, toSpringSort(query.sort))
        val page = propertyJpaRepository.findByCity(city, pageable)
        return PageResult(content = page.content, total = page.totalElements)
    }

    override fun findAllByIds(ids: Collection<Long>): List<PropertyModel> =
        if (ids.isEmpty()) emptyList() else propertyJpaRepository.findAllById(ids).toList()

    override fun deleteById(id: Long) = propertyJpaRepository.deleteById(id)

    /**
     * 도메인 정렬 어휘 → JPA property 경로 변환.
     * **화이트리스트만 허용** — 미등록 키는 BAD_REQUEST 로 거절 (외부 입력의 임의 키가 500 으로 터지는 것 방지, Copilot #8).
     * `@Embedded` VO 의 정렬 경로는 `vo.value` 형태로 명시 (Copilot #5 — `Rating` 의 정렬 키는 `rating.value`).
     */
    private fun toSpringSort(keys: List<SortKey>): Sort {
        if (keys.isEmpty()) return Sort.unsorted()
        val orders = keys.map { key ->
            val column = ALLOWED_SORT_KEYS[key.property]
                ?: throw CoreException(
                    ErrorType.BAD_REQUEST,
                    "지원하지 않는 정렬 키입니다: ${key.property}",
                )
            when (key.direction) {
                SortDirection.ASC -> Sort.Order.asc(column)
                SortDirection.DESC -> Sort.Order.desc(column)
            }
        }
        return Sort.by(orders)
    }

    companion object {
        // 도메인 어휘 ↔ JPA 프로퍼티 경로 매핑.
        // @Embedded 타입은 내부 필드 경로를 명시 (예: rating → rating.value)
        private val ALLOWED_SORT_KEYS: Map<String, String> = mapOf(
            "wishCount" to "wishCount",
            "rating" to "rating.value",
            "name" to "name.value",
        )
    }
}
