package com.stayloop.domain.property

import com.stayloop.domain.BaseEntity
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table

/**
 * Property Aggregate 의 자식 entity. (`docs/design/03-class-diagram.md §1`, `04-erd.md §2.6`)
 * `is_main = TRUE` 행은 한 Property 당 0~1개만 — 도메인 레벨 보장 (Property.replaceMainImage).
 */
@Entity
@Table(
    name = "property_images",
    indexes = [Index(name = "idx_property_images", columnList = "property_id, display_order")],
)
class PropertyImageModel internal constructor(
    propertyId: Long,
    imageUrl: String,
    altText: String? = null,
    displayOrder: Int = 0,
    isMain: Boolean = false,
) : BaseEntity() {

    @Column(name = "property_id", nullable = false)
    var propertyId: Long = propertyId
        protected set

    @Column(name = "image_url", nullable = false, length = 500)
    var imageUrl: String = imageUrl
        protected set

    @Column(name = "alt_text", length = 255)
    var altText: String? = altText
        protected set

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = displayOrder
        protected set

    @Column(name = "is_main", nullable = false)
    var isMain: Boolean = isMain
        protected set

    init {
        if (imageUrl.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "이미지 URL 은 비어 있을 수 없습니다.")
        }
        if (displayOrder < 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "display_order 는 0 이상이어야 합니다.")
        }
    }

    internal fun markAsMain() {
        isMain = true
    }

    internal fun unmarkAsMain() {
        isMain = false
    }

    companion object {
        fun create(
            propertyId: Long,
            imageUrl: String,
            altText: String? = null,
            displayOrder: Int = 0,
            isMain: Boolean = false,
        ): PropertyImageModel = PropertyImageModel(propertyId, imageUrl, altText, displayOrder, isMain)
    }
}
