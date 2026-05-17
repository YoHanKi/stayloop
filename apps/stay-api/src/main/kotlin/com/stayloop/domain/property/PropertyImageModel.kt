package com.stayloop.domain.property

import com.stayloop.domain.BaseEntity
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table

/**
 * Property 의 이미지. **별도 Aggregate Root** (`docs/design/03-class-diagram.md §1`, `04-erd.md §2.6`).
 *
 * **AR 분리 정책 (PR3, `docs/plan/week5-b.md` Loop 8'')**: 다른 도메인 (RoomType / Wishlist / Reservation) 과
 * 동일한 패턴 — *부모의 `@OneToMany` 매핑 없음*, *`propertyId: Long` 일반 컬럼으로 참조*. PropertyModel 의
 * `@JoinColumn` 기반 관계는 PR3 에서 제거 (자식 컬렉션 자동 관리의 *암시적 라이프사이클* 이 도메인 경계를
 * 흐림 — `addImage` 호출이 JPA dirty checking 으로 INSERT 발화하는 *암시적 흐름* 보다 명시적 Repository 호출이
 * 추적 / 테스트 가능성 우위).
 *
 * **이미지 조회**: `PropertyImageRepository.findByPropertyId(propertyId)` — Facade 가 명시 호출. `displayOrder
 * ASC, id ASC` 정렬 (안정 정렬).
 *
 * **`mainImageUrl` 캐시 (PropertyModel.mainImageUrl)**: search projection 의 비정규화 필드. `is_main = TRUE`
 * 인 PropertyImage 의 URL 을 *Property 가 들고 있는 캐시*. 이미지 변경 시 *Facade 가 양쪽 sync* (현 라운드
 * 미구현 — 어드민 image 관리 합류 시점에 Facade method 로 추가, week6+ 인계).
 *
 * **`is_main = TRUE` 한 Property 당 0~1개** — *DB-level UNIQUE 미적용*. 도메인 보장은 Facade 의 image 관리
 * 메서드 (미구현) 에서 *조회 후 unmark → mark* 순서로 강제 (future). 본 라운드에선 *seed 데이터* 만 image
 * 가지므로 가드 부재가 운영 위험 X.
 *
 * **컬럼 length ↔ `init` length 가드** — 동일 상수(`IMAGE_URL_MAX_LENGTH` / `ALT_TEXT_MAX_LENGTH`) 로 묶여
 * DB 제약 위반(500) 대신 BAD_REQUEST 로 거절 (verify-code §6).
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
        if (propertyId <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "propertyId 는 양수여야 합니다.")
        }
    }

    companion object {
        const val IMAGE_URL_MAX_LENGTH: Int = 500
        const val ALT_TEXT_MAX_LENGTH: Int = 255

        fun create(
            propertyId: Long,
            imageUrl: String,
            altText: String? = null,
            displayOrder: Int = 0,
            isMain: Boolean = false,
        ): PropertyImageModel = PropertyImageModel(
            propertyId = propertyId,
            imageUrl = imageUrl,
            altText = altText,
            displayOrder = displayOrder,
            isMain = isMain,
        )
    }
}
