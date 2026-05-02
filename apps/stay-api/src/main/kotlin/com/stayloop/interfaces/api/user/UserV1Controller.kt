package com.stayloop.interfaces.api.user

import com.stayloop.application.user.UserFacade
import com.stayloop.interfaces.api.ApiResponse
import com.stayloop.interfaces.api.auth.LoginCredentials
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/users")
class UserV1Controller(
    private val userFacade: UserFacade,
) : UserV1ApiSpec {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun signUp(
        @RequestBody request: UserV1Dto.SignUpRequest,
    ): ApiResponse<UserV1Dto.UserResponse> {
        return userFacade.signUp(request.toCommand())
            .let { UserV1Dto.UserResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping("/me")
    override fun getMyInfo(credentials: LoginCredentials): ApiResponse<UserV1Dto.UserResponse> {
        return userFacade.getMyInfo(credentials.loginId, credentials.rawPassword)
            .let { UserV1Dto.UserResponse.from(it) }
            .let { ApiResponse.success(it) }
    }

    @PatchMapping("/me/password")
    override fun changePassword(
        credentials: LoginCredentials,
        @RequestBody request: UserV1Dto.ChangePasswordRequest,
    ): ApiResponse<UserV1Dto.UserResponse> {
        return userFacade.changePassword(request.toCommand(credentials.loginId))
            .let { UserV1Dto.UserResponse.from(it) }
            .let { ApiResponse.success(it) }
    }
}
