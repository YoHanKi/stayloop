package com.stayloop.application.user.command

import com.stayloop.domain.user.value.LoginId

data class ChangePasswordCommand(
    val loginId: LoginId,
    val currentRawPassword: String,
    val newRawPassword: String,
)
