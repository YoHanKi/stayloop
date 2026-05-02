package com.stayloop.domain.user

import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.Name
import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import com.stayloop.support.test.FakePasswordEncoder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

class UserTest {
    private val encoder = FakePasswordEncoder()

    private fun newUser(rawPassword: String = "Abcd1234!"): User =
        User.create(
            loginId = LoginId("alen01"),
            rawPassword = rawPassword,
            name = Name("홍길동"),
            birthDate = BirthDate(LocalDate.of(2000, 1, 1)),
            email = Email("alen@stayloop.io"),
            phoneNumber = PhoneNumber("010-1234-5678"),
            encoder = encoder,
        )

    @DisplayName("create() 시 비밀번호는 인코더로 인코딩되어 저장된다.")
    @Test
    fun shouldEncodePasswordOnCreate() {
        val user = newUser("Abcd1234!")

        assertThat(user.password.encoded).isEqualTo("${FakePasswordEncoder.PREFIX}Abcd1234!")
    }

    @DisplayName("authenticate() 는 raw 비밀번호가 인코딩된 값과 일치하지 않으면 UNAUTHORIZED 로 거절한다.")
    @Test
    fun shouldRejectAuthenticate_whenPasswordMismatch() {
        val user = newUser("Abcd1234!")

        assertThatThrownBy { user.authenticate("Wrong1234!", encoder) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.UNAUTHORIZED)
    }

    @DisplayName("changePassword() 는 현재 비밀번호 검증 후 새 비밀번호로 교체한다.")
    @Test
    fun shouldChangePasswordSuccessfully() {
        // arrange
        val user = newUser("Abcd1234!")

        // act
        user.changePassword(currentRaw = "Abcd1234!", newRaw = "NewPass99@", encoder = encoder)

        // assert
        assertThat(user.password.encoded).isEqualTo("${FakePasswordEncoder.PREFIX}NewPass99@")
    }

    @DisplayName("changePassword() 시 현재 비밀번호가 틀리면 UNAUTHORIZED 로 거절한다.")
    @Test
    fun shouldRejectChangePassword_whenCurrentPasswordMismatch() {
        val user = newUser("Abcd1234!")

        assertThatThrownBy { user.changePassword("Wrong1234!", "NewPass99@", encoder) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.UNAUTHORIZED)
    }

    @DisplayName("changePassword() 시 새 비밀번호가 현재 비밀번호와 동일하면 BAD_REQUEST 로 거절한다.")
    @Test
    fun shouldRejectChangePassword_whenNewIsSameAsCurrent() {
        val user = newUser("Abcd1234!")

        assertThatThrownBy { user.changePassword("Abcd1234!", "Abcd1234!", encoder) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("changePassword() 시 새 비밀번호가 정책에 어긋나면 BAD_REQUEST 로 거절한다.")
    @Test
    fun shouldRejectChangePassword_whenNewViolatesPolicy() {
        val user = newUser("Abcd1234!")

        assertThatThrownBy { user.changePassword("Abcd1234!", "weak", encoder) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
