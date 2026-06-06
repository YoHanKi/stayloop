package com.stayloop.domain.wishlist

import java.io.Serializable

/**
 * [WishlistModel] 의 `@IdClass` 복합 키 `(userId, propertyId)`. 중복 찜을 식별자 수준에서 막는다.
 */
data class WishlistId(
    val userId: Long = 0,
    val propertyId: Long = 0,
) : Serializable
