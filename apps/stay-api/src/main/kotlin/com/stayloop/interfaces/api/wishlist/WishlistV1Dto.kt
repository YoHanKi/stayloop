package com.stayloop.interfaces.api.wishlist

import com.stayloop.application.wishlist.WishlistItemInfo
import com.stayloop.application.wishlist.WishlistToggleInfo

class WishlistV1Dto {
    data class ToggleResponse(
        val propertyId: Long,
        val wished: Boolean,
        val wishCount: Int,
    ) {
        companion object {
            fun from(info: WishlistToggleInfo): ToggleResponse =
                ToggleResponse(info.propertyId, info.wished, info.wishCount)
        }
    }

    data class WishItemResponse(
        val propertyId: Long,
        val name: String,
        val city: String,
        val mainImageUrl: String?,
        val wishedAt: String,
    ) {
        companion object {
            fun from(info: WishlistItemInfo): WishItemResponse =
                WishItemResponse(info.propertyId, info.name, info.city, info.mainImageUrl, info.wishedAt)
        }
    }
}
