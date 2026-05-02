package com.stayloop.interfaces.api.user

import com.stayloop.application.user.UserService
import com.stayloop.interfaces.api.ApiResponse
import com.stayloop.interfaces.api.auth.LoginCredentials
import com.stayloop.interfaces.api.user.dto.ChangePasswordRequest
import com.stayloop.interfaces.api.user.dto.SignUpRequest
import com.stayloop.interfaces.api.user.dto.UserResponse
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
class UserController(
    private val userService: UserService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun signUp(
        @RequestBody request: SignUpRequest,
    ): ApiResponse<UserResponse> {
        val info = userService.signUp(request.toCommand())
        return ApiResponse.success(UserResponse.from(info))
    }

    @GetMapping("/me")
    fun getMyInfo(credentials: LoginCredentials): ApiResponse<UserResponse> {
        val info = userService.getMyInfo(credentials.loginId, credentials.rawPassword)
        return ApiResponse.success(UserResponse.from(info))
    }

    @PatchMapping("/me/password")
    fun changePassword(
        credentials: LoginCredentials,
        @RequestBody request: ChangePasswordRequest,
    ): ApiResponse<UserResponse> {
        val info = userService.changePassword(request.toCommand(credentials.loginId))
        return ApiResponse.success(UserResponse.from(info))
    }
}
