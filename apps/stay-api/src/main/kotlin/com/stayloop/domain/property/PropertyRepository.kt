package com.stayloop.domain.property

interface PropertyRepository {
    fun save(property: PropertyModel): PropertyModel

    fun findById(id: Long): PropertyModel?

    fun findAllByIds(ids: List<Long>): List<PropertyModel>

    fun findByCity(city: String, page: Int, size: Int): List<PropertyModel>

    fun countByCity(city: String): Long

    /** 찜 수를 원자적으로 1 늘리고 갱신된 행 수를 돌려준다(비정규화 카운터 동시 증감 정합, 04-b §5-4). */
    fun incrementWishCount(propertyId: Long): Int

    /** 찜 수를 원자적으로 1 줄인다 — `wish_count > 0` 일 때만. 갱신된 행 수를 돌려준다(음수 진입 차단). */
    fun decrementWishCount(propertyId: Long): Int

    /** 현재 찜 수를 조회한다(벌크 갱신 직후 신선한 값을 응답에 싣기 위한 스칼라 조회). 없으면 null. */
    fun findWishCount(propertyId: Long): Int?
}
