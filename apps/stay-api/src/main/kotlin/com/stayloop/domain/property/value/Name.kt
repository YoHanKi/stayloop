package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * 숙소·객실 타입의 명칭. 1주차의 `domain/user/value/Name` 과 의미·길이가 다르므로 별도 정의.
 */
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

    override fun toString(): String = value

    companion object {
        private const val MAX_LENGTH = 100
    }
}
