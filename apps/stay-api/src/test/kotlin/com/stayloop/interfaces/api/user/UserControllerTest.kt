package com.stayloop.interfaces.api.user

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.stayloop.application.user.UserInfo
import com.stayloop.application.user.UserService
import com.stayloop.application.user.command.ChangePasswordCommand
import com.stayloop.application.user.command.SignUpCommand
import com.stayloop.domain.user.value.LoginId
import com.stayloop.interfaces.api.ApiControllerAdvice
import com.stayloop.interfaces.api.auth.LoginCredentials
import com.stayloop.interfaces.api.auth.LoginCredentialsArgumentResolver
import com.stayloop.interfaces.api.auth.WebMvcConfig
import com.stayloop.interfaces.api.user.dto.ChangePasswordRequest
import com.stayloop.interfaces.api.user.dto.SignUpRequest
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(controllers = [UserController::class])
@Import(WebMvcConfig::class, LoginCredentialsArgumentResolver::class, ApiControllerAdvice::class)
class UserControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {
    @MockkBean
    private lateinit var userService: UserService

    private val sampleInfo = UserInfo(
        loginId = "alen01",
        maskedName = "홍길*",
        birthDate = "2000-01-01",
        email = "alen@stayloop.io",
        maskedPhoneNumber = "010-****-5678",
    )

    @DisplayName("POST /api/v1/users 는 회원가입 후 201 과 마스킹된 응답을 돌려준다.")
    @Test
    fun shouldSignUpAndReturnCreated() {
        // arrange
        val captured = slot<SignUpCommand>()
        every { userService.signUp(capture(captured)) } returns sampleInfo
        val body = SignUpRequest(
            loginId = "alen01",
            password = "Abcd1234!",
            name = "홍길동",
            birthDate = "2000-01-01",
            email = "alen@stayloop.io",
            phoneNumber = "010-1234-5678",
        )

        // act / assert
        mockMvc.perform(
            post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
            .andExpect(jsonPath("$.data.loginId").value("alen01"))
            .andExpect(jsonPath("$.data.name").value("홍길*"))
            .andExpect(jsonPath("$.data.phoneNumber").value("010-****-5678"))

        verify(exactly = 1) { userService.signUp(any()) }
    }

    @DisplayName("POST /api/v1/users 는 잘못된 형식의 휴대폰 번호를 BAD_REQUEST 로 응답한다.")
    @Test
    fun shouldReturnBadRequest_whenPhoneFormatInvalid() {
        val body = SignUpRequest(
            loginId = "alen01",
            password = "Abcd1234!",
            name = "홍길동",
            birthDate = "2000-01-01",
            email = "alen@stayloop.io",
            phoneNumber = "01012345678",
        )

        mockMvc.perform(
            post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.meta.result").value("FAIL"))
    }

    @DisplayName("POST /api/v1/users 는 중복 로그인 ID 에 대해 CONFLICT 로 응답한다.")
    @Test
    fun shouldReturnConflict_whenLoginIdAlreadyExists() {
        every { userService.signUp(any()) } throws CoreException(ErrorType.CONFLICT, "이미 사용 중인 로그인 ID 입니다.")
        val body = SignUpRequest(
            loginId = "alen01",
            password = "Abcd1234!",
            name = "홍길동",
            birthDate = "2000-01-01",
            email = "alen@stayloop.io",
            phoneNumber = "010-1234-5678",
        )

        mockMvc.perform(
            post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.meta.result").value("FAIL"))
    }

    @DisplayName("GET /api/v1/users/me 는 인증 헤더가 누락되면 UNAUTHORIZED 로 응답한다.")
    @Test
    fun shouldReturnUnauthorized_whenHeadersMissing() {
        mockMvc.perform(get("/api/v1/users/me"))
            .andExpect(status().isUnauthorized)
    }

    @DisplayName("GET /api/v1/users/me 는 인증 성공 시 마스킹된 응답을 돌려준다.")
    @Test
    fun shouldReturnMyInfo_whenAuthenticated() {
        every { userService.getMyInfo(LoginId("alen01"), "Abcd1234!") } returns sampleInfo

        mockMvc.perform(
            get("/api/v1/users/me")
                .header(LoginCredentials.LOGIN_ID_HEADER, "alen01")
                .header(LoginCredentials.LOGIN_PW_HEADER, "Abcd1234!"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("홍길*"))
            .andExpect(jsonPath("$.data.phoneNumber").value("010-****-5678"))
    }

    @DisplayName("GET /api/v1/users/me 는 비밀번호 불일치 시 UNAUTHORIZED 로 응답한다.")
    @Test
    fun shouldReturnUnauthorized_whenPasswordMismatch() {
        every { userService.getMyInfo(LoginId("alen01"), "Wrong1234!") } throws
            CoreException(ErrorType.UNAUTHORIZED, "로그인 ID 또는 비밀번호가 일치하지 않습니다.")

        mockMvc.perform(
            get("/api/v1/users/me")
                .header(LoginCredentials.LOGIN_ID_HEADER, "alen01")
                .header(LoginCredentials.LOGIN_PW_HEADER, "Wrong1234!"),
        )
            .andExpect(status().isUnauthorized)
    }

    @DisplayName("PATCH /api/v1/users/me/password 는 인증 후 변경된 정보를 돌려준다.")
    @Test
    fun shouldChangePassword() {
        val captured = slot<ChangePasswordCommand>()
        every { userService.changePassword(capture(captured)) } returns sampleInfo
        val body = ChangePasswordRequest(currentPassword = "Abcd1234!", newPassword = "NewPass99@")

        mockMvc.perform(
            patch("/api/v1/users/me/password")
                .header(LoginCredentials.LOGIN_ID_HEADER, "alen01")
                .header(LoginCredentials.LOGIN_PW_HEADER, "Abcd1234!")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.loginId").value("alen01"))

        verify(exactly = 1) {
            userService.changePassword(
                match {
                    it.loginId.value == "alen01" &&
                        it.currentRawPassword == "Abcd1234!" &&
                        it.newRawPassword == "NewPass99@"
                },
            )
        }
    }

    @DisplayName("PATCH /api/v1/users/me/password 는 동일 비밀번호 변경 시 BAD_REQUEST 로 응답한다.")
    @Test
    fun shouldReturnBadRequest_whenNewPasswordSameAsCurrent() {
        every { userService.changePassword(any()) } throws
            CoreException(ErrorType.BAD_REQUEST, "현재 비밀번호와 동일한 비밀번호로는 변경할 수 없습니다.")
        val body = ChangePasswordRequest(currentPassword = "Abcd1234!", newPassword = "Abcd1234!")

        mockMvc.perform(
            patch("/api/v1/users/me/password")
                .header(LoginCredentials.LOGIN_ID_HEADER, "alen01")
                .header(LoginCredentials.LOGIN_PW_HEADER, "Abcd1234!")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)),
        )
            .andExpect(status().isBadRequest)
    }
}
