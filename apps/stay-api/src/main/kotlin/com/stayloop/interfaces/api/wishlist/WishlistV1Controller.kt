package com.stayloop.interfaces.api.wishlist

import com.stayloop.application.wishlist.WishlistFacade
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.user.value.LoginId
import com.stayloop.interfaces.api.ApiResponse
import com.stayloop.interfaces.api.auth.LoginUser
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class WishlistV1Controller(
    private val wishlistFacade: WishlistFacade,
) : WishlistV1ApiSpec {
    @PostMapping("/api/v1/properties/{propertyId}/wishes")
    @ResponseStatus(HttpStatus.CREATED)
    override fun wish(
        loginUser: LoginUser,
        @PathVariable propertyId: Long,
    ): ApiResponse<WishlistV1Dto.WishToggleResponse> {
        return wishlistFacade.wish(loginUser.loginId, propertyId)
            .let { WishlistV1Dto.WishToggleResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @DeleteMapping("/api/v1/properties/{propertyId}/wishes")
    override fun unwish(
        loginUser: LoginUser,
        @PathVariable propertyId: Long,
    ): ApiResponse<WishlistV1Dto.WishToggleResponse> {
        return wishlistFacade.unwish(loginUser.loginId, propertyId)
            .let { WishlistV1Dto.WishToggleResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping("/api/v1/users/{userId}/wishes")
    override fun getMyWishes(
        loginUser: LoginUser,
        @PathVariable userId: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<List<WishlistV1Dto.WishItemResponse>> {
        val target = LoginId(userId)
        val query = PageQuery(page = page, size = size)
        return wishlistFacade.getMyWishes(loginUser.loginId, target, query)
            .map(WishlistV1Dto.WishItemResponse::from)
            .let { ApiResponse.success(it) }
    }
}
