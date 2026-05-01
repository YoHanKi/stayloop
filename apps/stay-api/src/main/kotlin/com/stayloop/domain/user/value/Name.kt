package com.stayloop.domain.user.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
data class Name(
    @Column(name = "name", nullable = false, length = MAX_LENGTH)
    val value: String,
) {
    init {
        if (value.isBlank() || value.length > MAX_LENGTH) {
            throw CoreException(ErrorType.BAD_REQUEST, "이름은 1~$MAX_LENGTH 자의 비공백 문자열이어야 합니다.")
        }
    }

    fun masked(): String =
        when {
            value.length <= 1 -> "*"
            else -> value.dropLast(1) + "*"
        }

    override fun toString(): String = value

    companion object {
        private const val MAX_LENGTH = 50
    }
}
