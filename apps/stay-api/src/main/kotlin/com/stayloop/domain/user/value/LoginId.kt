package com.stayloop.domain.user.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
data class LoginId(
    @Column(name = "login_id", nullable = false, length = 20)
    val value: String,
) {
    init {
        if (!REGEX.matches(value)) {
            throw CoreException(ErrorType.BAD_REQUEST, "로그인 ID는 영문 대/소문자와 숫자로 4~20자여야 합니다.")
        }
    }

    override fun toString(): String = value

    companion object {
        private val REGEX = Regex("^[A-Za-z0-9]{4,20}$")
    }
}
