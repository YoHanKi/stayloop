package com.stayloop.interfaces.api.auth

import com.stayloop.domain.user.value.LoginId

data class LoginCredentials(
    val loginId: LoginId,
    val rawPassword: String,
) {
    companion object {
        const val LOGIN_ID_HEADER = "X-Loopers-LoginId"
        const val LOGIN_PW_HEADER = "X-Loopers-LoginPw"
    }
}
