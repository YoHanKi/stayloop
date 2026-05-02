package com.stayloop.interfaces.api.user

import com.stayloop.interfaces.api.ApiResponse
import com.stayloop.interfaces.api.auth.LoginCredentials
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "User V1 API", description = "Stayloop 회원 API 입니다.")
interface UserV1ApiSpec {
    @Operation(
        summary = "회원 가입",
        description = "신규 회원을 등록하고 마스킹된 회원 정보를 반환합니다.",
    )
    fun signUp(
        request: UserV1Dto.SignUpRequest,
    ): ApiResponse<UserV1Dto.UserResponse>

    @Operation(
        summary = "내 정보 조회",
        description = "로그인 ID/비밀번호 헤더로 인증 후 마스킹된 내 정보를 반환합니다.",
    )
    fun getMyInfo(
        credentials: LoginCredentials,
    ): ApiResponse<UserV1Dto.UserResponse>

    @Operation(
        summary = "비밀번호 변경",
        description = "현재 비밀번호로 인증 후 새 비밀번호로 교체합니다.",
    )
    fun changePassword(
        credentials: LoginCredentials,
        request: UserV1Dto.ChangePasswordRequest,
    ): ApiResponse<UserV1Dto.UserResponse>
}
