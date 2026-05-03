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
 *
 * **`property_id` 컬럼은 부모의 `@JoinColumn(name="property_id")` 가 단독 관리** (`insertable = false, updatable = false`).
 * 자식 모델이 부모 id 를 직접 들고 있으면 신규 Property(영속화 전 id=0) 에서 0 으로 굳어버리는 버그가 발생.
 *
 * **컬럼 length ↔ `init` length 가드** 는 동일 상수(`IMAGE_URL_MAX_LENGTH`, `ALT_TEXT_MAX_LENGTH`) 로 묶여
 * DB 제약 위반(500) 대신 BAD_REQUEST 로 거절된다 (verify-code §6).
 */
@Entity
@Table(
    name = "property_images",
    indexes = [Index(name = "idx_property_images", columnList = "property_id, display_order")],
)
class PropertyImageModel internal constructor(
    imageUrl: String,
    altText: String? = null,
    displayOrder: Int = 0,
    isMain: Boolean = false,
) : BaseEntity() {

    /**
     * 부모 PropertyModel 의 `@JoinColumn` 이 실제로 채운다. 도메인 코드에서 직접 set 금지.
     */
    @Column(name = "property_id", nullable = false, insertable = false, updatable = false)
    var propertyId: Long = 0L
        protected set

    @Column(name = "image_url", nullable = false, length = IMAGE_URL_MAX_LENGTH)
    var imageUrl: String = imageUrl
        protected set

    @Column(name = "alt_text", length = ALT_TEXT_MAX_LENGTH)
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
        if (imageUrl.length > IMAGE_URL_MAX_LENGTH) {
            throw CoreException(ErrorType.BAD_REQUEST, "이미지 URL 은 ${IMAGE_URL_MAX_LENGTH}자 이하여야 합니다.")
        }
        if (altText != null && altText.length > ALT_TEXT_MAX_LENGTH) {
            throw CoreException(ErrorType.BAD_REQUEST, "이미지 alt 텍스트는 ${ALT_TEXT_MAX_LENGTH}자 이하여야 합니다.")
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
        const val IMAGE_URL_MAX_LENGTH: Int = 500
        const val ALT_TEXT_MAX_LENGTH: Int = 255

        fun create(
            imageUrl: String,
            altText: String? = null,
            displayOrder: Int = 0,
            isMain: Boolean = false,
        ): PropertyImageModel = PropertyImageModel(imageUrl, altText, displayOrder, isMain)
    }
}
