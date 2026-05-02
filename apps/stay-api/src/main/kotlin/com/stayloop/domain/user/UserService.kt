package com.stayloop.domain.user

import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.Name
import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Service

@Service
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    fun signUp(
        loginId: LoginId,
        rawPassword: String,
        name: Name,
        birthDate: BirthDate,
        email: Email,
        phoneNumber: PhoneNumber,
    ): UserModel {
        if (userRepository.existsByLoginId(loginId)) {
            throw CoreException(ErrorType.CONFLICT, "이미 사용 중인 로그인 ID 입니다.")
        }
        val user = UserModel.create(
            loginId = loginId,
            rawPassword = rawPassword,
            name = name,
            birthDate = birthDate,
            email = email,
            phoneNumber = phoneNumber,
            encoder = passwordEncoder,
        )
        return userRepository.save(user)
    }

    fun authenticate(loginId: LoginId, rawPassword: String): UserModel {
        val user = userRepository.findByLoginId(loginId)
            ?: throw CoreException(ErrorType.UNAUTHORIZED, "로그인 ID 또는 비밀번호가 일치하지 않습니다.")
        user.authenticate(rawPassword, passwordEncoder)
        return user
    }

    fun changePassword(loginId: LoginId, currentRawPassword: String, newRawPassword: String): UserModel {
        val user = authenticate(loginId, currentRawPassword)
        user.changePassword(
            currentRaw = currentRawPassword,
            newRaw = newRawPassword,
            encoder = passwordEncoder,
        )
        return userRepository.save(user)
    }
}
