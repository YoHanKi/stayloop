package com.stayloop.domain.user.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

class BirthDateTest {
    @DisplayName("미래 날짜면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenDateIsInFuture() {
        val tomorrow = LocalDate.now().plusDays(1)

        assertThatThrownBy { BirthDate(tomorrow) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("1900년 이전이면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenYearIsBefore1900() {
        assertThatThrownBy { BirthDate(LocalDate.of(1899, 12, 31)) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("compact() 는 yyyyMMdd 포맷 문자열을 반환한다.")
    @Test
    fun shouldReturnCompactString() {
        assertThat(BirthDate(LocalDate.of(2000, 1, 5)).compact()).isEqualTo("20000105")
    }

    @DisplayName("of(iso) 는 yyyy-MM-dd 문자열을 파싱한다. 잘못된 포맷은 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldParseIsoString() {
        // arrange / act
        val parsed = BirthDate.of("2000-01-05")

        // assert
        assertThat(parsed.value).isEqualTo(LocalDate.of(2000, 1, 5))

        // act / assert
        assertThatThrownBy { BirthDate.of("2000/01/05") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
