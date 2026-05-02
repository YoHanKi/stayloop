package com.stayloop.application.user

import com.stayloop.application.user.command.ChangePasswordCommand
import com.stayloop.application.user.command.SignUpCommand
import com.stayloop.domain.user.UserService
import com.stayloop.domain.user.value.LoginId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserFacade(
    private val userService: UserService,
) {
    @Transactional
    fun signUp(command: SignUpCommand): UserInfo {
        val user = userService.signUp(
            loginId = command.loginId,
            rawPassword = command.rawPassword,
            name = command.name,
            birthDate = command.birthDate,
            email = command.email,
            phoneNumber = command.phoneNumber,
        )
        return UserInfo.from(user)
    }

    @Transactional(readOnly = true)
    fun getMyInfo(loginId: LoginId, rawPassword: String): UserInfo {
        val user = userService.authenticate(loginId, rawPassword)
        return UserInfo.from(user)
    }

    @Transactional
    fun changePassword(command: ChangePasswordCommand): UserInfo {
        val user = userService.changePassword(
            loginId = command.loginId,
            currentRawPassword = command.currentRawPassword,
            newRawPassword = command.newRawPassword,
        )
        return UserInfo.from(user)
    }
}
