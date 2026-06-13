package com.stayloop.interfaces.api.wishlist

import com.stayloop.interfaces.api.ApiResponse
import com.stayloop.interfaces.api.auth.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Wishlist V1 API", description = "Stayloop 찜 API 입니다.")
interface WishlistV1ApiSpec {
    @Operation(summary = "찜 추가", description = "숙소를 찜합니다. 두 번 호출해도 한 번만 반영됩니다(멱등).")
    fun wish(loginUser: LoginUser, propertyId: Long): ApiResponse<WishlistV1Dto.ToggleResponse>

    @Operation(summary = "찜 취소", description = "숙소 찜을 취소합니다. 두 번 호출해도 한 번만 반영됩니다(멱등).")
    fun unwish(loginUser: LoginUser, propertyId: Long): ApiResponse<WishlistV1Dto.ToggleResponse>

    @Operation(summary = "내 찜 목록", description = "본인의 찜 목록을 최신순으로 조회합니다. 타인 목록은 403.")
    fun getMyWishes(
        loginUser: LoginUser,
        userId: String,
        page: Int,
        size: Int,
    ): ApiResponse<List<WishlistV1Dto.WishItemResponse>>
}
