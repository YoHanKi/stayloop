package com.stayloop.domain.rate

import com.stayloop.domain.common.value.Money
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate

class DailyRoomRateModelTest {
    private val anyDate = LocalDate.of(2026, 5, 10)

    @DisplayName("정상 생성 시 pricePerNight 가 그대로 노출된다.")
    @Test
    fun shouldExposePricePerNight() {
        val rate = DailyRoomRateModel.create(roomTypeId = 1L, date = anyDate, pricePerNight = Money.of(120_000L))

        assertThat(rate.pricePerNight).isEqualTo(Money.of(120_000L))
        assertThat(rate.roomTypeId).isEqualTo(1L)
        assertThat(rate.date).isEqualTo(anyDate)
    }

    @DisplayName("roomTypeId 가 0 이하(0 / 음수) 이면 BAD_REQUEST 로 거절된다 — 영속화된 RoomType 참조 필수.")
    @ParameterizedTest
    @ValueSource(longs = [0L, -1L, -100L])
    fun shouldReject_whenRoomTypeIdIsZeroOrNegative(roomTypeId: Long) {
        assertThatThrownBy {
            DailyRoomRateModel.create(roomTypeId = roomTypeId, date = anyDate, pricePerNight = Money.of(100_000L))
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("음수 가격은 Money 도메인 가드에서 BAD_REQUEST 로 거절된다 — Rate 가 별도 검증을 중복하지 않음.")
    @Test
    fun shouldReject_whenPriceIsNegative_viaMoneyGuard() {
        // Money(-1) 자체에서 throw — DailyRoomRate 까지 가지 않음
        assertThatThrownBy { Money.of(-1L) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("0원 가격은 허용된다 — 무료 객실 / 프로모션 시나리오 자리.")
    @Test
    fun shouldAllowZeroPrice() {
        val rate = DailyRoomRateModel.create(roomTypeId = 1L, date = anyDate, pricePerNight = Money.ZERO)

        assertThat(rate.pricePerNight.isZero()).isTrue
    }
}
