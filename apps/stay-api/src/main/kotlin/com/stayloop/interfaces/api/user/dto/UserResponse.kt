package com.stayloop.interfaces.api.user.dto

import com.stayloop.application.user.UserInfo

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
