package com.stayloop.application.wishlist

/**
 * 찜 토글 결과 — 토글 후의 찜 여부와 숙소의 비정규화 찜 수.
 */
data class WishlistToggleInfo(
    val propertyId: Long,
    val wished: Boolean,
    val wishCount: Int,
)

/**
 * 내 찜 목록 한 건.
 */
data class WishlistItemInfo(
    val propertyId: Long,
    val name: String,
    val city: String,
    val mainImageUrl: String?,
    val wishedAt: String,
)
