package com.stayloop.application.wishlist

import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.wishlist.WishlistModel
import java.time.LocalDateTime

/**
 * 찜 토글 결과. (`docs/design/02-sequence-diagram.md §4`)
 *
 * `wished` 는 토글 *직후* 의 상태 — POST /wishes 후 true, DELETE /wishes 후 false. 멱등 호출에서도 같은 값.
 */
data class WishlistToggleInfo(
    val propertyId: Long,
    val wished: Boolean,
    val wishCount: Int,
)

/**
 * 사용자의 찜 목록 한 항목. 검색 결과처럼 *대표 정보* 까지 펼쳐 응답.
 */
data class WishlistItemInfo(
    val propertyId: Long,
    val propertyName: String,
    val city: String,
    val mainImageUrl: String?,
    val rating: Double,
    val wishCount: Int,
    val wishedAt: LocalDateTime,
) {
    companion object {
        fun of(wishlist: WishlistModel, property: PropertyModel): WishlistItemInfo = WishlistItemInfo(
            propertyId = property.id,
            propertyName = property.name.value,
            city = property.address.city,
            mainImageUrl = property.mainImageUrl,
            rating = property.rating.value,
            wishCount = property.wishCount,
            wishedAt = wishlist.wishedAt,
        )
    }
}
