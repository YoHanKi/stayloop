package com.stayloop.interfaces.api.coupon

import com.stayloop.application.coupon.CouponFacade
import com.stayloop.application.coupon.command.IssueCouponCommand
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.interfaces.api.ApiResponse
import com.stayloop.interfaces.api.auth.LoginUser
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class CouponV1Controller(
    private val couponFacade: CouponFacade,
) : CouponV1ApiSpec {
    @PostMapping("/api/v1/coupons/{couponId}/issue")
    @ResponseStatus(HttpStatus.CREATED)
    override fun issue(
        loginUser: LoginUser,
        @PathVariable couponId: Long,
    ): ApiResponse<CouponV1Dto.CouponIssueResponse> {
        val command = IssueCouponCommand(actor = loginUser.loginId, templateId = couponId)
        return couponFacade.issue(command)
            .let { CouponV1Dto.CouponIssueResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping("/api/v1/users/me/coupons")
    override fun getMyCoupons(
        loginUser: LoginUser,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<List<CouponV1Dto.CouponIssueResponse>> {
        val query = PageQuery(page = page, size = size)
        return couponFacade.getMyCoupons(loginUser.loginId, query)
            .map(CouponV1Dto.CouponIssueResponse::from)
            .let { ApiResponse.success(it) }
    }
}
