package com.stayloop.application.user

import com.stayloop.domain.user.UserModel

data class UserInfo(
    val loginId: String,
    val maskedName: String,
    val birthDate: String,
    val email: String,
    val maskedPhoneNumber: String,
) {
    companion object {
        fun from(user: UserModel): UserInfo =
            UserInfo(
                loginId = user.loginId.value,
                maskedName = user.name.masked(),
                birthDate = user.birthDate.isoString(),
                email = user.email.value,
                maskedPhoneNumber = user.phoneNumber.masked(),
            )
    }
}
