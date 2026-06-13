package com.stayloop.domain.property

import com.stayloop.domain.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * 숙소 이미지 갤러리 항목. [PropertyModel] 의 자식 entity 로, 생명주기를 Property 와 공유한다.
 *
 * 대표 이미지(`isMain`) 전이는 [markAsMain] / [unmarkAsMain] 이 `internal` 이라 외부에서
 * 직접 못 부른다. 한 Property 당 대표 이미지 0~1 불변식은 [PropertyModel.replaceMainImage] 가
 * 컬렉션 차원에서 보장한다.
 */
@Entity
@Table(name = "property_images")
class PropertyImageModel internal constructor(
    imageUrl: String,
    isMain: Boolean,
) : BaseEntity() {

    var imageUrl: String = imageUrl
        protected set

    var isMain: Boolean = isMain
        protected set

    internal fun markAsMain() {
        isMain = true
    }

    internal fun unmarkAsMain() {
        isMain = false
    }

    companion object {
        fun create(imageUrl: String, isMain: Boolean = false): PropertyImageModel =
            PropertyImageModel(imageUrl = imageUrl, isMain = isMain)
    }
}
