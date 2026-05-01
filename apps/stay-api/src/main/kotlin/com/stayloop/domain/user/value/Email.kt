package com.stayloop.domain.user.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
data class Email(
    @Column(name = "email", nullable = false, length = 100)
    val value: String,
) {
    init {
        if (!REGEX.matches(value)) {
            throw CoreException(ErrorType.BAD_REQUEST, "이메일 형식이 올바르지 않습니다.")
        }
    }

    override fun toString(): String = value

    companion object {
        private val REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }
}
