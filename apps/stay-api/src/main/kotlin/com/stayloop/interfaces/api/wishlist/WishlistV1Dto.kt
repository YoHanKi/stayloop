package com.stayloop.interfaces.api.wishlist

import com.stayloop.application.wishlist.WishlistItemInfo
import com.stayloop.application.wishlist.WishlistToggleInfo
import java.time.LocalDateTime

class WishlistV1Dto {
    data class WishToggleResponse(
        val propertyId: Long,
        val wished: Boolean,
        val wishCount: Int,
    ) {
        companion object {
            fun from(info: WishlistToggleInfo): WishToggleResponse = WishToggleResponse(
                propertyId = info.propertyId,
                wished = info.wished,
                wishCount = info.wishCount,
            )
        }
    }

    data class WishItemResponse(
        val propertyId: Long,
        val propertyName: String,
        val city: String,
        val mainImageUrl: String?,
        val rating: Double,
        val wishCount: Int,
        val wishedAt: LocalDateTime,
    ) {
        companion object {
            fun from(info: WishlistItemInfo): WishItemResponse = WishItemResponse(
                propertyId = info.propertyId,
                propertyName = info.propertyName,
                city = info.city,
                mainImageUrl = info.mainImageUrl,
                rating = info.rating,
                wishCount = info.wishCount,
                wishedAt = info.wishedAt,
            )
        }
    }
}
