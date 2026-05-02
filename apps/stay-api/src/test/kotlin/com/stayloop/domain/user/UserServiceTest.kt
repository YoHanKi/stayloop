package com.stayloop.domain.user

import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.Name
import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import com.stayloop.support.test.FakePasswordEncoder
import com.stayloop.support.test.InMemoryUserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

class UserServiceTest {
    private lateinit var userRepository: InMemoryUserRepository
    private lateinit var encoder: FakePasswordEncoder
    private lateinit var sut: UserService

    @BeforeEach
    fun setUp() {
        userRepository = InMemoryUserRepository()
        encoder = FakePasswordEncoder()
        sut = UserService(userRepository, encoder)
    }

    private fun signUp(
        loginId: String = "alen01",
        rawPassword: String = "Abcd1234!",
    ): UserModel = sut.signUp(
        loginId = LoginId(loginId),
        rawPassword = rawPassword,
        name = Name("홍길동"),
        birthDate = BirthDate(LocalDate.of(2000, 1, 1)),
        email = Email("alen@stayloop.io"),
        phoneNumber = PhoneNumber("010-1234-5678"),
    )

    @DisplayName("signUp() 은 회원을 인코딩된 비밀번호로 저장한다.")
    @Test
    fun shouldSignUpAndPersistEncodedPassword() {
        val user = signUp()

        assertThat(user.loginId.value).isEqualTo("alen01")
        assertThat(user.password.encoded).isEqualTo("${FakePasswordEncoder.PREFIX}Abcd1234!")
    }

    @DisplayName("signUp() 시 동일 로그인 ID 가 이미 존재하면 CONFLICT 로 거절한다.")
    @Test
    fun shouldReject_whenLoginIdAlreadyExists() {
        signUp()

        assertThatThrownBy { signUp() }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("authenticate() 는 비밀번호가 일치하면 사용자를 반환한다.")
    @Test
    fun shouldReturnUser_whenPasswordMatches() {
        signUp(rawPassword = "Abcd1234!")

        val user = sut.authenticate(LoginId("alen01"), "Abcd1234!")

        assertThat(user.loginId.value).isEqualTo("alen01")
    }

    @DisplayName("authenticate() 는 존재하지 않는 사용자에 대해서도 UNAUTHORIZED 로 응답한다 (존재 노출 금지).")
    @Test
    fun shouldReturnUnauthorized_whenUserNotFound() {
        assertThatThrownBy { sut.authenticate(LoginId("ghost1"), "Abcd1234!") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.UNAUTHORIZED)
    }

    @DisplayName("authenticate() 는 비밀번호 불일치 시 UNAUTHORIZED 로 거절한다.")
    @Test
    fun shouldReturnUnauthorized_whenPasswordMismatch() {
        signUp(rawPassword = "Abcd1234!")

        assertThatThrownBy { sut.authenticate(LoginId("alen01"), "Wrong1234!") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.UNAUTHORIZED)
    }

    @DisplayName("changePassword() 는 인증 후 새 비밀번호로 교체한다.")
    @Test
    fun shouldChangePasswordSuccessfully() {
        signUp(rawPassword = "Abcd1234!")

        sut.changePassword(LoginId("alen01"), "Abcd1234!", "NewPass99@")

        sut.authenticate(LoginId("alen01"), "NewPass99@") // does not throw
        assertThatThrownBy { sut.authenticate(LoginId("alen01"), "Abcd1234!") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.UNAUTHORIZED)
    }

    @DisplayName("changePassword() 시 새 비밀번호가 현재 비밀번호와 동일하면 BAD_REQUEST 로 거절한다.")
    @Test
    fun shouldReject_whenNewPasswordIsSameAsCurrent() {
        signUp(rawPassword = "Abcd1234!")

        assertThatThrownBy { sut.changePassword(LoginId("alen01"), "Abcd1234!", "Abcd1234!") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("changePassword() 시 현재 비밀번호가 틀리면 UNAUTHORIZED 로 거절한다.")
    @Test
    fun shouldReject_whenCurrentPasswordMismatch() {
        signUp(rawPassword = "Abcd1234!")

        assertThatThrownBy { sut.changePassword(LoginId("alen01"), "Wrong1234!", "NewPass99@") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.UNAUTHORIZED)
    }
}
