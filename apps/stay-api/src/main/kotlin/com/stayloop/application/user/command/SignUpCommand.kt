package com.stayloop.application.user.command

import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.Name
import com.stayloop.domain.user.value.PhoneNumber

data class SignUpCommand(
    val loginId: LoginId,
    val rawPassword: String,
    val name: Name,
    val birthDate: BirthDate,
    val email: Email,
    val phoneNumber: PhoneNumber,
)
