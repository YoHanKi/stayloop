package com.stayloop.support.test

import com.stayloop.domain.user.UserModel
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.Name
import com.stayloop.domain.user.value.PhoneNumber
import java.time.LocalDate

/**
 * 테스트에서 유효한 회원을 손쉽게 만들고 저장하기 위한 픽스처.
 */
object UserFixture {
    fun save(repository: UserRepository, loginId: String): UserModel {
        val user = UserModel.create(
            loginId = LoginId(loginId),
            rawPassword = "Abcd1234!",
            name = Name("홍길동"),
            birthDate = BirthDate(LocalDate.of(2000, 1, 1)),
            email = Email("$loginId@stayloop.io"),
            phoneNumber = PhoneNumber("010-1234-5678"),
            encoder = FakePasswordEncoder(),
        )
        return repository.save(user)
    }
}
