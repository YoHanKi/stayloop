package com.stayloop.domain.property

/**
 * PropertyImage AR 의 Repository. (`docs/plan/week5-b.md` Loop 8'' — PR3 에서 별도 AR 로 분리)
 *
 * **위상**: PropertyImageModel 은 별도 AR — PropertyModel 의 `@OneToMany` 없음. Facade 가 본 Repository 를
 * 명시 호출해 조회한다.
 */
interface PropertyImageRepository {
    /**
     * Property 의 이미지 목록을 *안정 정렬* (displayOrder ASC, id ASC) 로 반환.
     * 인덱스 `idx_property_images (property_id, display_order)` prefix scan 정합.
     */
    fun findByPropertyId(propertyId: Long): List<PropertyImageModel>

    fun save(image: PropertyImageModel): PropertyImageModel

    fun deleteById(id: Long)
}
