package com.stayloop.interfaces.api.user

import com.stayloop.application.user.UserInfo
import com.stayloop.application.user.command.ChangePasswordCommand
import com.stayloop.application.user.command.SignUpCommand
import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.Name
import com.stayloop.domain.user.value.PhoneNumber

class UserV1Dto {
    data class SignUpRequest(
        val loginId: String,
        val password: String,
        val name: String,
        val birthDate: String,
        val email: String,
        val phoneNumber: String,
    ) {
        fun toCommand(): SignUpCommand =
            SignUpCommand(
                loginId = LoginId(loginId),
                rawPassword = password,
                name = Name(name),
                birthDate = BirthDate.of(birthDate),
                email = Email(email),
                phoneNumber = PhoneNumber(phoneNumber),
            )
    }

    data class ChangePasswordRequest(
        val currentPassword: String,
        val newPassword: String,
    ) {
        fun toCommand(loginId: LoginId): ChangePasswordCommand =
            ChangePasswordCommand(
                loginId = loginId,
                currentRawPassword = currentPassword,
                newRawPassword = newPassword,
            )
    }

    data class UserResponse(
        val loginId: String,
        val name: String,
        val birthDate: String,
        val email: String,
        val phoneNumber: String,
    ) {
        companion object {
            fun from(info: UserInfo): UserResponse =
                UserResponse(
                    loginId = info.loginId,
                    name = info.maskedName,
                    birthDate = info.birthDate,
                    email = info.email,
                    phoneNumber = info.maskedPhoneNumber,
                )
        }
    }
}
