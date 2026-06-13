package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
data class PropertyName(
    @Column(name = "name", nullable = false, length = 100)
    val value: String,
) {
    init {
        if (value.isBlank() || value.length > MAX_LENGTH) {
            throw CoreException(ErrorType.BAD_REQUEST, "숙소 이름은 1~$MAX_LENGTH 자여야 합니다.")
        }
    }

    override fun toString(): String = value

    companion object {
        private const val MAX_LENGTH = 100
    }
}
