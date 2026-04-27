package com.stayloop.interfaces.api.user.dto

import com.stayloop.application.user.command.SignUpCommand
import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.Name
import com.stayloop.domain.user.value.PhoneNumber

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
