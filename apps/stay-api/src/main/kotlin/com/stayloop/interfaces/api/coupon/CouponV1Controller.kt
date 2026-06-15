package com.stayloop.interfaces.api.coupon

import com.stayloop.application.coupon.CouponFacade
import com.stayloop.application.coupon.command.IssueCouponCommand
import com.stayloop.interfaces.api.ApiResponse
import com.stayloop.interfaces.api.auth.LoginUser
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/coupons")
class CouponV1Controller(
    private val couponFacade: CouponFacade,
) : CouponV1ApiSpec {
    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    override fun createTemplate(
        @RequestBody request: CouponV1Dto.CreateTemplateRequest,
    ): ApiResponse<CouponV1Dto.TemplateResponse> =
        couponFacade.createTemplate(request.toCommand())
            .let { CouponV1Dto.TemplateResponse.from(it) }
            .let { ApiResponse.success(it) }

    @PostMapping("/templates/{templateId}/issue")
    @ResponseStatus(HttpStatus.CREATED)
    override fun issue(
        loginUser: LoginUser,
        @PathVariable templateId: Long,
    ): ApiResponse<CouponV1Dto.IssuedCouponResponse> =
        couponFacade.issue(IssueCouponCommand(loginUser.loginId, templateId))
            .let { CouponV1Dto.IssuedCouponResponse.from(it) }
            .let { ApiResponse.success(it) }

    @GetMapping
    override fun getMyCoupons(
        loginUser: LoginUser,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<List<CouponV1Dto.IssuedCouponResponse>> =
        couponFacade.getMyCoupons(loginUser.loginId, page, size)
            .map { CouponV1Dto.IssuedCouponResponse.from(it) }
            .let { ApiResponse.success(it) }
}
