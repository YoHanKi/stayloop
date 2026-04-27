package com.stayloop.application.user

import com.stayloop.application.user.command.ChangePasswordCommand
import com.stayloop.application.user.command.SignUpCommand
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

    private fun signUpCommand(
        loginId: String = "alen01",
        rawPassword: String = "Abcd1234!",
    ) = SignUpCommand(
        loginId = LoginId(loginId),
        rawPassword = rawPassword,
        name = Name("홍길동"),
        birthDate = BirthDate(LocalDate.of(2000, 1, 1)),
        email = Email("alen@stayloop.io"),
        phoneNumber = PhoneNumber("010-1234-5678"),
    )

    @DisplayName("signUp() 은 회원을 저장하고 마스킹된 정보를 반환한다.")
    @Test
    fun shouldSignUpAndReturnMaskedInfo() {
        // act
        val info = sut.signUp(signUpCommand())

        // assert
        assertThat(info.loginId).isEqualTo("alen01")
        assertThat(info.maskedName).isEqualTo("홍길*")
        assertThat(info.maskedPhoneNumber).isEqualTo("010-****-5678")
        assertThat(info.email).isEqualTo("alen@stayloop.io")
        assertThat(info.birthDate).isEqualTo("2000-01-01")
    }

    @DisplayName("signUp() 시 동일 로그인 ID 가 이미 존재하면 CONFLICT 로 거절한다.")
    @Test
    fun shouldReject_whenLoginIdAlreadyExists() {
        // arrange
        sut.signUp(signUpCommand())

        // act / assert
        assertThatThrownBy { sut.signUp(signUpCommand()) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("getMyInfo() 는 비밀번호가 일치하면 마스킹된 정보를 반환한다.")
    @Test
    fun shouldReturnMyInfo_whenPasswordMatches() {
        // arrange
        sut.signUp(signUpCommand(rawPassword = "Abcd1234!"))

        // act
        val info = sut.getMyInfo(LoginId("alen01"), "Abcd1234!")

        // assert
        assertThat(info.maskedName).isEqualTo("홍길*")
        assertThat(info.maskedPhoneNumber).isEqualTo("010-****-5678")
    }

    @DisplayName("getMyInfo() 는 존재하지 않는 사용자에 대해서도 UNAUTHORIZED 로 응답한다 (존재 노출 금지).")
    @Test
    fun shouldReturnUnauthorized_whenUserNotFound() {
        assertThatThrownBy { sut.getMyInfo(LoginId("ghost1"), "Abcd1234!") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.UNAUTHORIZED)
    }

    @DisplayName("getMyInfo() 는 비밀번호 불일치 시 UNAUTHORIZED 로 거절한다.")
    @Test
    fun shouldReturnUnauthorized_whenPasswordMismatch() {
        sut.signUp(signUpCommand(rawPassword = "Abcd1234!"))

        assertThatThrownBy { sut.getMyInfo(LoginId("alen01"), "Wrong1234!") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.UNAUTHORIZED)
    }

    @DisplayName("changePassword() 는 인증 후 새 비밀번호로 교체하고 변경된 정보를 반환한다.")
    @Test
    fun shouldChangePasswordSuccessfully() {
        // arrange
        sut.signUp(signUpCommand(rawPassword = "Abcd1234!"))

        // act
        sut.changePassword(
            ChangePasswordCommand(
                loginId = LoginId("alen01"),
                currentRawPassword = "Abcd1234!",
                newRawPassword = "NewPass99@",
            ),
        )

        // assert
        sut.getMyInfo(LoginId("alen01"), "NewPass99@") // does not throw
        assertThatThrownBy { sut.getMyInfo(LoginId("alen01"), "Abcd1234!") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.UNAUTHORIZED)
    }

    @DisplayName("changePassword() 시 새 비밀번호가 현재 비밀번호와 동일하면 BAD_REQUEST 로 거절한다.")
    @Test
    fun shouldReject_whenNewPasswordIsSameAsCurrent() {
        sut.signUp(signUpCommand(rawPassword = "Abcd1234!"))

        assertThatThrownBy {
            sut.changePassword(
                ChangePasswordCommand(
                    loginId = LoginId("alen01"),
                    currentRawPassword = "Abcd1234!",
                    newRawPassword = "Abcd1234!",
                ),
            )
        }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("changePassword() 시 현재 비밀번호가 틀리면 UNAUTHORIZED 로 거절한다.")
    @Test
    fun shouldReject_whenCurrentPasswordMismatch() {
        sut.signUp(signUpCommand(rawPassword = "Abcd1234!"))

        assertThatThrownBy {
            sut.changePassword(
                ChangePasswordCommand(
                    loginId = LoginId("alen01"),
                    currentRawPassword = "Wrong1234!",
                    newRawPassword = "NewPass99@",
                ),
            )
        }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.UNAUTHORIZED)
    }
}
