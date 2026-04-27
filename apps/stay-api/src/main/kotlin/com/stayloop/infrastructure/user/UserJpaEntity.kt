package com.stayloop.infrastructure.user

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.user.User
import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.Name
import com.stayloop.domain.user.value.Password
import com.stayloop.domain.user.value.PhoneNumber
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate

@Entity
@Table(
    name = "users",
    uniqueConstraints = [UniqueConstraint(name = "uk_users_login_id", columnNames = ["login_id"])],
)
class UserJpaEntity(
    @Column(name = "login_id", nullable = false, length = 20)
    val loginId: String,
    @Column(name = "password", nullable = false, length = 100)
    var password: String,
    @Column(name = "name", nullable = false, length = 50)
    val name: String,
    @Column(name = "birth_date", nullable = false)
    val birthDate: LocalDate,
    @Column(name = "email", nullable = false, length = 100)
    val email: String,
    @Column(name = "phone_number", nullable = false, length = 20)
    val phoneNumber: String,
) : BaseEntity() {
    fun toDomain(): User =
        User.reconstruct(
            id = id,
            loginId = LoginId(loginId),
            password = Password.ofEncoded(password),
            name = Name(name),
            birthDate = BirthDate(birthDate),
            email = Email(email),
            phoneNumber = PhoneNumber(phoneNumber),
        )

    companion object {
        fun fromDomain(user: User): UserJpaEntity =
            UserJpaEntity(
                loginId = user.loginId.value,
                password = user.password.encoded,
                name = user.name.value,
                birthDate = user.birthDate.value,
                email = user.email.value,
                phoneNumber = user.phoneNumber.value,
            )
    }
}
