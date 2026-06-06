package com.stayloop.domain.property

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.property.value.Address
import com.stayloop.domain.property.value.Amenities
import com.stayloop.domain.property.value.PropertyCategory
import com.stayloop.domain.property.value.PropertyName
import com.stayloop.domain.property.value.PropertyPolicy
import com.stayloop.domain.property.value.Rating
import com.stayloop.domain.property.value.StarRating
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

/**
 * 숙소 Aggregate Root. RoomType 은 별도 AR 로 떼고(03 §0) 여기서는 [PropertyImageModel] 갤러리만
 * 자식으로 묶는다. `wishCount` 는 검색용 비정규화 캐시이며 집계값 동시성 정확성은 4주차 영역이다.
 */
@Entity
@Table(name = "properties")
class PropertyModel internal constructor(
    name: PropertyName,
    category: PropertyCategory,
    description: String?,
    address: Address,
    amenities: Amenities,
    policy: PropertyPolicy,
    starRating: StarRating?,
    rating: Rating,
) : BaseEntity() {

    @Embedded
    var name: PropertyName = name
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    var category: PropertyCategory = category
        protected set

    @Column(name = "description", columnDefinition = "TEXT")
    var description: String? = description
        protected set

    @Embedded
    var address: Address = address
        protected set

    @Column(name = "amenities", columnDefinition = "TEXT")
    var amenities: Amenities = amenities
        protected set

    @Column(name = "policy", columnDefinition = "TEXT")
    var policy: PropertyPolicy = policy
        protected set

    @Column(name = "main_image_url", length = 500)
    var mainImageUrl: String? = null
        protected set

    @Embedded
    var starRating: StarRating? = starRating
        protected set

    @Embedded
    var rating: Rating = rating
        protected set

    @Column(name = "wish_count", nullable = false)
    var wishCount: Int = 0
        protected set

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true)
    @JoinColumn(name = "property_id")
    private val mutableImages: MutableList<PropertyImageModel> = mutableListOf()

    val images: List<PropertyImageModel>
        get() = mutableImages.toList()

    /** 갤러리에 이미지를 추가한다. [isMain] 이면 기존 대표를 해제하고 이 이미지를 대표로 둔다. */
    fun addImage(imageUrl: String, isMain: Boolean = false) {
        val image = PropertyImageModel.create(imageUrl, isMain)
        if (isMain) {
            mutableImages.forEach { it.unmarkAsMain() }
            mainImageUrl = imageUrl
        }
        mutableImages.add(image)
    }

    /** 갤러리에 이미 존재하는 이미지를 단일 대표로 만든다. 대표 이미지 0~1 불변식을 보장한다. */
    fun replaceMainImage(imageUrl: String) {
        val target = mutableImages.firstOrNull { it.imageUrl == imageUrl }
            ?: throw CoreException(ErrorType.NOT_FOUND, "대표로 지정할 이미지가 갤러리에 없습니다.")
        mutableImages.forEach { it.unmarkAsMain() }
        target.markAsMain()
        mainImageUrl = imageUrl
    }

    fun incrementWishCount() {
        wishCount += 1
    }

    /** 찜 취소. 멱등 흐름에서 카운트가 음수로 새지 않게 0 에서는 no-op. */
    fun decrementWishCount() {
        if (wishCount > 0) {
            wishCount -= 1
        }
    }

    companion object {
        fun create(
            name: PropertyName,
            category: PropertyCategory,
            address: Address,
            policy: PropertyPolicy,
            description: String? = null,
            amenities: Amenities = Amenities.EMPTY,
            starRating: StarRating? = null,
            rating: Rating = Rating.ZERO,
        ): PropertyModel =
            PropertyModel(
                name = name,
                category = category,
                description = description,
                address = address,
                amenities = amenities,
                policy = policy,
                starRating = starRating,
                rating = rating,
            )
    }
}
