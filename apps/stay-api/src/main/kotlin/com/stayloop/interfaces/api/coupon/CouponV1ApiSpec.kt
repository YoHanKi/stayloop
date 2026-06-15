package com.stayloop.interfaces.api.coupon

import com.stayloop.interfaces.api.ApiResponse
import com.stayloop.interfaces.api.auth.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Coupon V1 API", description = "Stayloop 쿠폰 API 입니다.")
interface CouponV1ApiSpec {
    @Operation(summary = "쿠폰 템플릿 생성", description = "선착순 발급 쿠폰 캠페인(할인·한정 수량)을 만듭니다.")
    fun createTemplate(request: CouponV1Dto.CreateTemplateRequest): ApiResponse<CouponV1Dto.TemplateResponse>

    @Operation(summary = "쿠폰 발급", description = "선착순 발급. 소진 시 409, 동일인 중복 발급 시 409.")
    fun issue(loginUser: LoginUser, templateId: Long): ApiResponse<CouponV1Dto.IssuedCouponResponse>

    @Operation(summary = "내 쿠폰 목록", description = "본인이 발급받은 쿠폰을 최신 발급순으로 조회합니다.")
    fun getMyCoupons(loginUser: LoginUser, page: Int, size: Int): ApiResponse<List<CouponV1Dto.IssuedCouponResponse>>
}
