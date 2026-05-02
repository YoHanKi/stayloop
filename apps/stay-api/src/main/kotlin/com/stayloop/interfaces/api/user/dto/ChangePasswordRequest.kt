package com.stayloop.interfaces.api.user.dto

import com.stayloop.application.user.command.ChangePasswordCommand
import com.stayloop.domain.user.value.LoginId

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
