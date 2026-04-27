package com.stayloop.domain.user.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@JvmInline
value class BirthDate(val value: LocalDate) {
    init {
        if (value.isAfter(LocalDate.now())) {
            throw CoreException(ErrorType.BAD_REQUEST, "생년월일은 미래 날짜일 수 없습니다.")
        }
        if (value.year < MIN_YEAR) {
            throw CoreException(ErrorType.BAD_REQUEST, "생년월일은 ${MIN_YEAR}년 이후여야 합니다.")
        }
    }

    fun compact(): String = value.format(COMPACT_FORMATTER)

    fun isoString(): String = value.format(ISO_FORMATTER)

    companion object {
        private const val MIN_YEAR = 1900
        private val COMPACT_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE

        fun of(isoString: String): BirthDate {
            val parsed = try {
                LocalDate.parse(isoString, ISO_FORMATTER)
            } catch (e: DateTimeParseException) {
                throw CoreException(ErrorType.BAD_REQUEST, "생년월일 형식이 올바르지 않습니다 (yyyy-MM-dd).")
            }
            return BirthDate(parsed)
        }
    }
}
