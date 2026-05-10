package com.stayloop.interfaces.api.coupon.admin

import com.stayloop.interfaces.api.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Coupon Admin V1 API", description = "Stayloop 어드민 쿠폰 템플릿 CRUD + 발급 이력 조회 API 입니다.")
interface CouponAdminV1ApiSpec {
    @Operation(summary = "쿠폰 템플릿 목록 조회", description = "id DESC 고정 정렬.")
    fun list(
        page: Int,
        size: Int,
    ): ApiResponse<List<CouponAdminV1Dto.TemplateResponse>>

    @Operation(summary = "쿠폰 템플릿 단건 조회")
    fun detail(couponId: Long): ApiResponse<CouponAdminV1Dto.TemplateResponse>

    @Operation(summary = "쿠폰 템플릿 등록", description = "code UNIQUE 충돌 시 CONFLICT.")
    fun register(
        request: CouponAdminV1Dto.RegisterRequest,
    ): ApiResponse<CouponAdminV1Dto.TemplateResponse>

    @Operation(summary = "쿠폰 템플릿 수정", description = "전체 필드 갱신 (PUT 의미).")
    fun update(
        couponId: Long,
        request: CouponAdminV1Dto.UpdateRequest,
    ): ApiResponse<CouponAdminV1Dto.TemplateResponse>

    @Operation(
        summary = "쿠폰 템플릿 삭제",
        description = "발급 이력이 있는 템플릿은 CONFLICT 로 거절됩니다 (soft delete 미도입).",
    )
    fun delete(couponId: Long): ApiResponse<Unit>

    @Operation(summary = "쿠폰 템플릿의 발급 이력 조회", description = "issuedAt DESC 고정 정렬.")
    fun listIssues(
        couponId: Long,
        page: Int,
        size: Int,
    ): ApiResponse<List<CouponAdminV1Dto.IssueResponse>>
}
