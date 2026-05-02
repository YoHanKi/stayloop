package com.stayloop.application.user

import com.stayloop.application.user.command.ChangePasswordCommand
import com.stayloop.application.user.command.SignUpCommand
import com.stayloop.domain.user.UserService
import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.Name
import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.support.test.FakePasswordEncoder
import com.stayloop.support.test.InMemoryUserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

class UserFacadeTest {
    private lateinit var sut: UserFacade

    @BeforeEach
    fun setUp() {
        val userRepository = InMemoryUserRepository()
        val encoder = FakePasswordEncoder()
        sut = UserFacade(UserService(userRepository, encoder))
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

    @DisplayName("signUp() 은 회원을 등록하고 마스킹된 정보를 반환한다.")
    @Test
    fun shouldSignUpAndReturnMaskedInfo() {
        val info = sut.signUp(signUpCommand())

        assertThat(info.loginId).isEqualTo("alen01")
        assertThat(info.maskedName).isEqualTo("홍길*")
        assertThat(info.maskedPhoneNumber).isEqualTo("010-****-5678")
        assertThat(info.email).isEqualTo("alen@stayloop.io")
        assertThat(info.birthDate).isEqualTo("2000-01-01")
    }

    @DisplayName("getMyInfo() 는 비밀번호가 일치하면 마스킹된 정보를 반환한다.")
    @Test
    fun shouldReturnMaskedInfo_whenPasswordMatches() {
        sut.signUp(signUpCommand(rawPassword = "Abcd1234!"))

        val info = sut.getMyInfo(LoginId("alen01"), "Abcd1234!")

        assertThat(info.maskedName).isEqualTo("홍길*")
        assertThat(info.maskedPhoneNumber).isEqualTo("010-****-5678")
    }

    @DisplayName("changePassword() 는 인증 후 새 비밀번호로 교체된 정보를 반환한다.")
    @Test
    fun shouldReturnInfoAfterChangePassword() {
        sut.signUp(signUpCommand(rawPassword = "Abcd1234!"))

        val info = sut.changePassword(
            ChangePasswordCommand(
                loginId = LoginId("alen01"),
                currentRawPassword = "Abcd1234!",
                newRawPassword = "NewPass99@",
            ),
        )

        assertThat(info.loginId).isEqualTo("alen01")
        // 새 비밀번호로 인증되는지 확인
        sut.getMyInfo(LoginId("alen01"), "NewPass99@")
    }
}
