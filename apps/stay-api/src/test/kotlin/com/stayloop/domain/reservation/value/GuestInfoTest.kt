package com.stayloop.domain.reservation.value

import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class GuestInfoTest {
    private val anyPhone = PhoneNumber("010-1234-5678")

    @DisplayName("정상 생성 시 name / phoneNumber 가 그대로 노출된다.")
    @Test
    fun shouldExposeFields() {
        val info = GuestInfo(name = "홍길동", phoneNumber = anyPhone)

        assertThat(info.name).isEqualTo("홍길동")
        assertThat(info.phoneNumber).isEqualTo(anyPhone)
    }

    @DisplayName("name 이 빈 문자열이거나 공백뿐이면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(strings = ["", " ", "   ", "\t"])
    fun shouldReject_whenNameIsBlank(name: String) {
        assertThatThrownBy { GuestInfo(name = name, phoneNumber = anyPhone) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("name 이 MAX_NAME_LENGTH(50) 자 이내면 정상 생성된다 — 경계값 정상 통과.")
    @Test
    fun shouldAccept_whenNameLengthIsAtMax() {
        val maxName = "가".repeat(GuestInfo.MAX_NAME_LENGTH)

        val info = GuestInfo(name = maxName, phoneNumber = anyPhone)

        assertThat(info.name).hasSize(GuestInfo.MAX_NAME_LENGTH)
    }

    @DisplayName("name 이 MAX_NAME_LENGTH(50) 자를 초과하면 BAD_REQUEST 로 거절된다 — 경계값 +1 거절.")
    @Test
    fun shouldReject_whenNameLengthExceedsMax() {
        val tooLong = "가".repeat(GuestInfo.MAX_NAME_LENGTH + 1)

        assertThatThrownBy { GuestInfo(name = tooLong, phoneNumber = anyPhone) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("phoneNumber 형식 위반은 PhoneNumber VO 가 자체 거절한다 — GuestInfo 가 중복 검증하지 않는다.")
    @Test
    fun shouldDelegatePhoneFormatValidation_toPhoneNumberVO() {
        // PhoneNumber("invalid") 자체에서 throw — GuestInfo 까지 도달하지 않음
        assertThatThrownBy { PhoneNumber("invalid-format") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
