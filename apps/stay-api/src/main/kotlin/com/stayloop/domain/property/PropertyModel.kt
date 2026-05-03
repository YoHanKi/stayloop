package com.stayloop.domain.property

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.property.value.Address
import com.stayloop.domain.property.value.Amenities
import com.stayloop.domain.property.value.Name
import com.stayloop.domain.property.value.PropertyCategory
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
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table

/**
 * 숙소 Aggregate Root. (`docs/design/03-class-diagram.md §1`)
 * 자식 entity: `PropertyImage` (갤러리). 다른 도메인(RoomType / Wishlist / Reservation) 은 별도 AR 로 ID 참조만.
 */
@Entity
@Table(
    name = "properties",
    indexes = [
        Index(name = "idx_properties_city", columnList = "city"),
        Index(name = "idx_properties_rating", columnList = "rating"),
        Index(name = "idx_properties_wish_count", columnList = "wish_count"),
    ],
)
class PropertyModel internal constructor(
    name: Name,
    category: PropertyCategory,
    description: String?,
    address: Address,
    amenities: Amenities,
    policy: PropertyPolicy,
    mainImageUrl: String? = null,
    starRating: StarRating? = null,
    rating: Rating = Rating.ZERO,
    wishCount: Int = 0,
) : BaseEntity() {

    @Embedded
    var name: Name = name
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

    // AmenitiesConverter / PropertyPolicyConverter 는 autoApply=true 로 무어노테이션 매핑
    @Column(name = "amenities", columnDefinition = "JSON", nullable = false)
    var amenities: Amenities = amenities
        protected set

    @Column(name = "policy", columnDefinition = "JSON", nullable = false)
    var policy: PropertyPolicy = policy
        protected set

    @Column(name = "main_image_url", length = MAIN_IMAGE_URL_MAX_LENGTH)
    var mainImageUrl: String? = mainImageUrl
        protected set

    @Embedded
    var starRating: StarRating? = starRating
        protected set

    @Embedded
    var rating: Rating = rating
        protected set

    @Column(name = "wish_count", nullable = false)
    var wishCount: Int = wishCount
        protected set

    /**
     * 이미지 갤러리 — Property AR 의 자식 entity.
     * 직접 노출하지 않고 `addImage` / `replaceMainImage` 등 메서드로만 변경.
     *
     * `@OrderBy("displayOrder ASC, id ASC")` — 1차 키(`displayOrder`)가 동률일 때
     * 2차 키(`id`)로 안정 정렬을 보장 (verify-code §4 — 단일 키만으로는 동률 비결정).
     */
    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id")
    @OrderBy("displayOrder ASC, id ASC")
    private val _images: MutableList<PropertyImageModel> = mutableListOf()

    val images: List<PropertyImageModel>
        get() = _images.toList()

    init {
        if (wishCount < 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "wishCount 는 음수일 수 없습니다.")
        }
        // 컬럼 length ↔ 도메인 가드 일관성: mainImageUrl 캐시도 컬럼 길이와 동일 한계 (verify-code §6).
        if (mainImageUrl != null && mainImageUrl.length > MAIN_IMAGE_URL_MAX_LENGTH) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "mainImageUrl 은 ${MAIN_IMAGE_URL_MAX_LENGTH}자 이하여야 합니다.",
            )
        }
    }

    /**
     * 찜 등록 시 카운트 증가. WishlistFacade 가 호출.
     */
    fun incrementWishCount() {
        wishCount += 1
    }

    /**
     * 찜 취소 시 카운트 감소. **음수 진입을 도메인 레벨에서 차단** (`docs/design/03 §1` 가드).
     * 멱등 흐름에서 미찜 상태에 unwish 가 잘못 호출되어도 DB 가 영구히 어긋나지 않게 한다.
     */
    fun decrementWishCount() {
        if (wishCount <= 0) {
            throw CoreException(ErrorType.CONFLICT, "wishCount 는 0 미만으로 감소할 수 없습니다.")
        }
        wishCount -= 1
    }

    /**
     * 갤러리에 이미지 추가. `isMain = true` 인 경우 기존 main 의 플래그를 해제.
     * 자식 entity 의 `propertyId` 는 부모의 `@JoinColumn` 이 채운다 — 호출자가 전달하지 않는다.
     *
     * **상태 변경 순서**: 검증/생성 → 기존 main 해제 → 컬렉션 추가 → 캐시 갱신.
     * `PropertyImageModel.create()` 가 예외를 던지면 aggregate 상태가 변하지 않아야 함 (`verify-code §6` 가드).
     */
    fun addImage(imageUrl: String, altText: String? = null, displayOrder: Int = 0, isMain: Boolean = false): PropertyImageModel {
        // 1) 입력 검증 + 자식 인스턴스 생성 — 실패 시 예외 (aggregate 상태 미변경)
        val image = PropertyImageModel.create(
            imageUrl = imageUrl,
            altText = altText,
            displayOrder = displayOrder,
            isMain = isMain,
        )
        // 2) 검증 통과 후에야 기존 main 해제 + 컬렉션 추가
        if (isMain) {
            _images.forEach { it.unmarkAsMain() }
        }
        _images.add(image)
        if (isMain) {
            mainImageUrl = imageUrl
        }
        return image
    }

    /**
     * 대표 이미지 교체. `mainImageUrl` 캐시 컬럼과 갤러리의 `is_main` 플래그를 함께 갱신 (`05 §2.0.3` 가드).
     * **갤러리에 없는 URL 은 거절** — 캐시 컬럼과 갤러리의 단일 진실 원천 보호 (Copilot #4 가드).
     */
    fun replaceMainImage(imageUrl: String) {
        if (imageUrl.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "대표 이미지 URL 은 비어 있을 수 없습니다.")
        }
        if (imageUrl.length > MAIN_IMAGE_URL_MAX_LENGTH) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "대표 이미지 URL 은 ${MAIN_IMAGE_URL_MAX_LENGTH}자 이하여야 합니다.",
            )
        }
        val target = _images.firstOrNull { it.imageUrl == imageUrl }
            ?: throw CoreException(
                ErrorType.BAD_REQUEST,
                "대표 이미지로 지정하려는 URL 이 갤러리에 없습니다: $imageUrl",
            )
        _images.forEach { it.unmarkAsMain() }
        target.markAsMain()
        mainImageUrl = imageUrl
    }

    companion object {
        const val MAIN_IMAGE_URL_MAX_LENGTH: Int = 500

        fun create(
            name: Name,
            category: PropertyCategory,
            description: String? = null,
            address: Address,
            amenities: Amenities = Amenities.EMPTY,
            policy: PropertyPolicy,
            mainImageUrl: String? = null,
            starRating: StarRating? = null,
        ): PropertyModel = PropertyModel(
            name = name,
            category = category,
            description = description,
            address = address,
            amenities = amenities,
            policy = policy,
            mainImageUrl = mainImageUrl,
            starRating = starRating,
        )
    }
}
