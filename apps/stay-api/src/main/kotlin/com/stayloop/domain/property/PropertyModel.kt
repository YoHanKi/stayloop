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
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table

/**
 * 숙소 Aggregate Root. (`docs/design/03-class-diagram.md §1`)
 *
 * **자식 entity 없음** (PR3 정책 변경). 다른 도메인 (RoomType / Wishlist / Reservation / **PropertyImage**) 은
 * *별도 AR 로 분리*, `propertyId: Long` 참조 컬럼으로 연결. `@OneToMany` / `@JoinColumn` 미사용 — *암시적
 * 라이프사이클 관리* (JPA dirty checking 의 자동 INSERT/DELETE) 보다 *명시적 Repository 호출* 우위.
 *
 * **`mainImageUrl` 캐시**: search projection 의 비정규화 필드. `PropertyImageModel` 의 `is_main = TRUE` row 의
 * URL 을 Property 가 *들고 있는 캐시*. 이미지 변경 흐름 (어드민) 합류 시 *Facade 가 양쪽 sync* (현 라운드
 * 미구현 — week6+ 인계).
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

    companion object {
        const val MAIN_IMAGE_URL_MAX_LENGTH: Int = 500

        /**
         * 신규 Property 생성 진입점.
         *
         * **`mainImageUrl` 캐시 컬럼은 매개변수로 받지 않는다** — 대표 이미지는 *PropertyImage AR* 의 `is_main = TRUE`
         * row 를 Facade 가 *별도 흐름* 으로 등록한 후 Property.mainImageUrl 을 sync (week6+ 인계). 신규 Property
         * 의 초기 상태는 *이미지 없음 → mainImageUrl null*.
         *
         * JPA hydration 은 internal constructor 가 처리 — 그 경로에서는 DB 가 캐시 값을 가져온다.
         */
        fun create(
            name: Name,
            category: PropertyCategory,
            description: String? = null,
            address: Address,
            amenities: Amenities = Amenities.EMPTY,
            policy: PropertyPolicy,
            starRating: StarRating? = null,
        ): PropertyModel = PropertyModel(
            name = name,
            category = category,
            description = description,
            address = address,
            amenities = amenities,
            policy = policy,
            mainImageUrl = null,
            starRating = starRating,
        )
    }
}
