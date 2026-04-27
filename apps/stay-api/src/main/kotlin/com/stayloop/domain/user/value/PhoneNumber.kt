package com.stayloop.domain.user.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType

@JvmInline
value class PhoneNumber(val value: String) {
    init {
        if (!REGEX.matches(value)) {
            throw CoreException(ErrorType.BAD_REQUEST, "휴대폰 번호는 010-XXXX-XXXX 형식이어야 합니다.")
        }
    }

    fun masked(): String {
        val parts = value.split("-")
        return "${parts[0]}-****-${parts[2]}"
    }

    override fun toString(): String = value

    companion object {
        private val REGEX = Regex("^010-\\d{4}-\\d{4}$")
    }
}
