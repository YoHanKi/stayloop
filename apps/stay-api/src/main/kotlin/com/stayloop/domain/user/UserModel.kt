package com.stayloop.domain.user

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.Name
import com.stayloop.domain.user.value.Password
import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "users",
    uniqueConstraints = [UniqueConstraint(name = "uk_users_login_id", columnNames = ["login_id"])],
)
class UserModel internal constructor(
    loginId: LoginId,
    password: Password,
    name: Name,
    birthDate: BirthDate,
    email: Email,
    phoneNumber: PhoneNumber,
) : BaseEntity() {

    @Embedded
    var loginId: LoginId = loginId
        protected set

    @Embedded
    var password: Password = password
        protected set

    @Embedded
    var name: Name = name
        protected set

    @Embedded
    var birthDate: BirthDate = birthDate
        protected set

    @Embedded
    var email: Email = email
        protected set

    @Embedded
    var phoneNumber: PhoneNumber = phoneNumber
        protected set

    fun authenticate(rawPassword: String, encoder: PasswordEncoder) {
        if (!password.matches(rawPassword, encoder)) {
            throw CoreException(ErrorType.UNAUTHORIZED, "로그인 ID 또는 비밀번호가 일치하지 않습니다.")
        }
    }

    fun changePassword(currentRaw: String, newRaw: String, encoder: PasswordEncoder) {
        authenticate(currentRaw, encoder)
        if (currentRaw == newRaw) {
            throw CoreException(ErrorType.BAD_REQUEST, "현재 비밀번호와 동일한 비밀번호로는 변경할 수 없습니다.")
        }
        password = Password.ofRaw(newRaw, birthDate, encoder)
    }

    companion object {
        fun create(
            loginId: LoginId,
            rawPassword: String,
            name: Name,
            birthDate: BirthDate,
            email: Email,
            phoneNumber: PhoneNumber,
            encoder: PasswordEncoder,
        ): UserModel =
            UserModel(
                loginId = loginId,
                password = Password.ofRaw(rawPassword, birthDate, encoder),
                name = name,
                birthDate = birthDate,
                email = email,
                phoneNumber = phoneNumber,
            )
    }
}
