package com.stayloop.interfaces.api.wishlist

import com.stayloop.application.wishlist.WishlistFacade
import com.stayloop.domain.user.value.LoginId
import com.stayloop.interfaces.api.ApiResponse
import com.stayloop.interfaces.api.auth.LoginUser
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class WishlistV1Controller(
    private val wishlistFacade: WishlistFacade,
) : WishlistV1ApiSpec {
    @PostMapping("/api/v1/properties/{propertyId}/wishes")
    override fun wish(
        loginUser: LoginUser,
        @PathVariable propertyId: Long,
    ): ApiResponse<WishlistV1Dto.ToggleResponse> =
        wishlistFacade.wish(loginUser.loginId, propertyId)
            .let { WishlistV1Dto.ToggleResponse.from(it) }
            .let { ApiResponse.success(it) }

    @DeleteMapping("/api/v1/properties/{propertyId}/wishes")
    override fun unwish(
        loginUser: LoginUser,
        @PathVariable propertyId: Long,
    ): ApiResponse<WishlistV1Dto.ToggleResponse> =
        wishlistFacade.unwish(loginUser.loginId, propertyId)
            .let { WishlistV1Dto.ToggleResponse.from(it) }
            .let { ApiResponse.success(it) }

    @GetMapping("/api/v1/users/{userId}/wishes")
    override fun getMyWishes(
        loginUser: LoginUser,
        @PathVariable userId: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<List<WishlistV1Dto.WishItemResponse>> =
        wishlistFacade.getMyWishes(requester = loginUser.loginId, target = LoginId(userId), page = page, size = size)
            .map { WishlistV1Dto.WishItemResponse.from(it) }
            .let { ApiResponse.success(it) }
}
