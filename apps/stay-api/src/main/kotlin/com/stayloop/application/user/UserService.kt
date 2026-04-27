package com.stayloop.application.user

import com.stayloop.application.user.command.ChangePasswordCommand
import com.stayloop.application.user.command.SignUpCommand
import com.stayloop.domain.user.PasswordEncoder
import com.stayloop.domain.user.User
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    @Transactional
    fun signUp(command: SignUpCommand): UserInfo {
        if (userRepository.existsByLoginId(command.loginId)) {
            throw CoreException(ErrorType.CONFLICT, "이미 사용 중인 로그인 ID 입니다.")
        }
        val user = User.create(
            loginId = command.loginId,
            rawPassword = command.rawPassword,
            name = command.name,
            birthDate = command.birthDate,
            email = command.email,
            phoneNumber = command.phoneNumber,
            encoder = passwordEncoder,
        )
        return UserInfo.from(userRepository.save(user))
    }

    @Transactional(readOnly = true)
    fun getMyInfo(loginId: LoginId, rawPassword: String): UserInfo {
        val user = authenticate(loginId, rawPassword)
        return UserInfo.from(user)
    }

    @Transactional
    fun changePassword(command: ChangePasswordCommand): UserInfo {
        val user = authenticate(command.loginId, command.currentRawPassword)
        user.changePassword(
            currentRaw = command.currentRawPassword,
            newRaw = command.newRawPassword,
            encoder = passwordEncoder,
        )
        return UserInfo.from(userRepository.save(user))
    }

    private fun authenticate(loginId: LoginId, rawPassword: String): User {
        val user = userRepository.findByLoginId(loginId)
            ?: throw CoreException(ErrorType.UNAUTHORIZED, "로그인 ID 또는 비밀번호가 일치하지 않습니다.")
        user.authenticate(rawPassword, passwordEncoder)
        return user
    }
}
