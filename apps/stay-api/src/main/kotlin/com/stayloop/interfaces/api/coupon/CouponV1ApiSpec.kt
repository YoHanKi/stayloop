package com.stayloop.interfaces.api.coupon

import com.stayloop.interfaces.api.ApiResponse
import com.stayloop.interfaces.api.auth.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Coupon V1 API", description = "Stayloop 대고객 쿠폰 발급 / 조회 API 입니다.")
interface CouponV1ApiSpec {
    @Operation(
        summary = "쿠폰 발급",
        description = "공개된 쿠폰 템플릿을 본인 계정으로 발급합니다. 만료된 템플릿은 BAD_REQUEST.",
    )
    fun issue(
        loginUser: LoginUser,
        couponId: Long,
    ): ApiResponse<CouponV1Dto.CouponIssueResponse>

    @Operation(
        summary = "내 쿠폰 목록 조회",
        description = "발급일 내림차순 고정 정렬. AVAILABLE / USED / EXPIRED 가 함께 반환됩니다 " +
            "(만료는 조회 시점 lazy 표현).",
    )
    fun getMyCoupons(
        loginUser: LoginUser,
        page: Int,
        size: Int,
    ): ApiResponse<List<CouponV1Dto.CouponIssueResponse>>
}
