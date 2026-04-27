package com.stayloop.domain.user.value

import com.stayloop.domain.user.PasswordEncoder
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType

class Password private constructor(val encoded: String) {
    fun matches(raw: String, encoder: PasswordEncoder): Boolean = encoder.matches(raw, encoded)

    override fun equals(other: Any?): Boolean = other is Password && encoded == other.encoded

    override fun hashCode(): Int = encoded.hashCode()

    override fun toString(): String = "Password(encoded=***)"

    companion object {
        private val POLICY_REGEX = Regex("^[A-Za-z0-9!@#\$%^&*()_+\\-=\\[\\]{};:'\",.<>/?\\\\|`~]{8,16}$")

        fun ofRaw(raw: String, birthDate: BirthDate, encoder: PasswordEncoder): Password {
            if (!POLICY_REGEX.matches(raw)) {
                throw CoreException(
                    ErrorType.BAD_REQUEST,
                    "비밀번호는 8~16자의 영문 대/소문자·숫자·특수문자만 사용 가능합니다.",
                )
            }
            if (raw.contains(birthDate.compact())) {
                throw CoreException(ErrorType.BAD_REQUEST, "비밀번호에 생년월일을 포함할 수 없습니다.")
            }
            return Password(encoder.encode(raw))
        }

        fun ofEncoded(encoded: String): Password = Password(encoded)
    }
}
