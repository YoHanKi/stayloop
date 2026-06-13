package com.stayloop.domain.reservation

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ReservationPriceCalculatorTest {
    private val calculator = ReservationPriceCalculator()

    private fun rate(day: Int, price: Long): DailyRoomRateModel =
        DailyRoomRateModel(roomTypeId = 1L, date = LocalDate.of(2026, 6, day), pricePerNight = Money.of(price))

    @DisplayName("여러 일자의 1박 요금을 모두 더한 총액을 돌려준다.")
    @Test
    fun shouldSumDailyRates() {
        val total = calculator.totalPrice(listOf(rate(1, 100_000), rate(2, 120_000), rate(3, 100_000)))

        assertThat(total).isEqualTo(Money.of(320_000))
    }

    @DisplayName("요금 목록이 비어 있으면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenRatesEmpty() {
        assertThatThrownBy { calculator.totalPrice(emptyList()) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
