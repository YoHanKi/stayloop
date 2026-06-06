package com.stayloop.domain.reservation.value

import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class GuestInfoTest {
    @DisplayName("이름과 연락처가 있으면 예약자 정보가 생성된다.")
    @Test
    fun shouldCreate_whenValid() {
        val guest = GuestInfo(name = "홍길동", phoneNumber = PhoneNumber("010-1234-5678"))

        assertThat(guest.name).isEqualTo("홍길동")
        assertThat(guest.phoneNumber.value).isEqualTo("010-1234-5678")
    }

    @DisplayName("예약자 이름이 공백이면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenNameBlank() {
        assertThatThrownBy { GuestInfo(name = " ", phoneNumber = PhoneNumber("010-1234-5678")) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
