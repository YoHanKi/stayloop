package com.stayloop.interfaces.api.coupon.admin

import com.stayloop.application.coupon.admin.CouponAdminFacade
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.interfaces.api.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class CouponAdminV1Controller(
    private val couponAdminFacade: CouponAdminFacade,
) : CouponAdminV1ApiSpec {

    @GetMapping("/api-admin/v1/coupons")
    override fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<List<CouponAdminV1Dto.TemplateResponse>> {
        val query = PageQuery(page = page, size = size)
        return couponAdminFacade.list(query)
            .map(CouponAdminV1Dto.TemplateResponse::from)
            .let { ApiResponse.success(it) }
    }

    @GetMapping("/api-admin/v1/coupons/{couponId}")
    override fun detail(
        @PathVariable couponId: Long,
    ): ApiResponse<CouponAdminV1Dto.TemplateResponse> {
        return couponAdminFacade.detail(couponId)
            .let { CouponAdminV1Dto.TemplateResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @PostMapping("/api-admin/v1/coupons")
    @ResponseStatus(HttpStatus.CREATED)
    override fun register(
        @RequestBody request: CouponAdminV1Dto.RegisterRequest,
    ): ApiResponse<CouponAdminV1Dto.TemplateResponse> {
        return couponAdminFacade.register(request.toCommand())
            .let { CouponAdminV1Dto.TemplateResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @PutMapping("/api-admin/v1/coupons/{couponId}")
    override fun update(
        @PathVariable couponId: Long,
        @RequestBody request: CouponAdminV1Dto.UpdateRequest,
    ): ApiResponse<CouponAdminV1Dto.TemplateResponse> {
        return couponAdminFacade.update(request.toCommand(couponId))
            .let { CouponAdminV1Dto.TemplateResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @DeleteMapping("/api-admin/v1/coupons/{couponId}")
    override fun delete(
        @PathVariable couponId: Long,
    ): ApiResponse<Unit> {
        couponAdminFacade.delete(couponId)
        return ApiResponse.success(Unit)
    }

    @GetMapping("/api-admin/v1/coupons/{couponId}/issues")
    override fun listIssues(
        @PathVariable couponId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<List<CouponAdminV1Dto.IssueResponse>> {
        val query = PageQuery(page = page, size = size)
        return couponAdminFacade.listIssues(couponId, query)
            .map(CouponAdminV1Dto.IssueResponse::from)
            .let { ApiResponse.success(it) }
    }
}
