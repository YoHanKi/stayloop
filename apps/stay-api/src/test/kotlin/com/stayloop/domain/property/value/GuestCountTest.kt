package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class GuestCountTest {
    @DisplayName("기준 인원이 1 미만이면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenBaseGuestsBelowOne() {
        assertThatThrownBy { GuestCount(baseGuests = 0, maxGuests = 2) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("최대 인원이 기준 인원보다 적으면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenMaxBelowBase() {
        assertThatThrownBy { GuestCount(baseGuests = 3, maxGuests = 2) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("요청 인원이 1~최대 인원 범위면 수용 가능하다.")
    @Test
    fun shouldAccommodate_whenWithinRange() {
        val guestCount = GuestCount(baseGuests = 2, maxGuests = 4)

        assertThat(guestCount.canAccommodate(1)).isTrue()
        assertThat(guestCount.canAccommodate(4)).isTrue()
    }

    @DisplayName("요청 인원이 0 이하이거나 최대 인원을 넘으면 수용 불가다.")
    @Test
    fun shouldNotAccommodate_whenOutOfRange() {
        val guestCount = GuestCount(baseGuests = 2, maxGuests = 4)

        assertThat(guestCount.canAccommodate(0)).isFalse()
        assertThat(guestCount.canAccommodate(5)).isFalse()
    }
}
