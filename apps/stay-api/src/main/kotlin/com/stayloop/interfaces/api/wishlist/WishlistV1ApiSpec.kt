package com.stayloop.interfaces.api.wishlist

import com.stayloop.interfaces.api.ApiResponse
import com.stayloop.interfaces.api.auth.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Wishlist V1 API", description = "Stayloop 찜 토글 API 입니다.")
interface WishlistV1ApiSpec {
    @Operation(
        summary = "숙소 찜 등록",
        description = "이미 찜된 경우 멱등하게 동일 응답을 반환합니다 (wishCount 증분 X).",
    )
    fun wish(
        loginUser: LoginUser,
        propertyId: Long,
    ): ApiResponse<WishlistV1Dto.WishToggleResponse>

    @Operation(
        summary = "숙소 찜 취소",
        description = "찜 되어 있지 않은 경우 멱등하게 동일 응답을 반환합니다 (wishCount 감소 X).",
    )
    fun unwish(
        loginUser: LoginUser,
        propertyId: Long,
    ): ApiResponse<WishlistV1Dto.WishToggleResponse>

    @Operation(
        summary = "본인 찜 목록 조회",
        description = "헤더의 LoginId 와 path 의 userId 가 일치해야 합니다 (FORBIDDEN). 정렬은 최근 찜 순 고정.",
    )
    fun getMyWishes(
        loginUser: LoginUser,
        userId: String,
        page: Int,
        size: Int,
    ): ApiResponse<List<WishlistV1Dto.WishItemResponse>>
}
