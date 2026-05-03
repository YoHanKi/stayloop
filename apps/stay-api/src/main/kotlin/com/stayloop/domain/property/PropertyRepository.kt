package com.stayloop.domain.property

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.common.value.PageResult

interface PropertyRepository {
    fun save(property: PropertyModel): PropertyModel

    fun findById(id: Long): PropertyModel?

    /**
     * 도시 코드로 페이징 조회. 정렬은 `query.sort` 의 도메인 어휘 (`wishCount`, `rating`, `name` 등)를
     * RepositoryImpl 가 인프라 정렬 키로 매핑한다.
     */
    fun findByCity(city: String, query: PageQuery): PageResult<PropertyModel>

    fun findAllByIds(ids: Collection<Long>): List<PropertyModel>

    fun deleteById(id: Long)
}
