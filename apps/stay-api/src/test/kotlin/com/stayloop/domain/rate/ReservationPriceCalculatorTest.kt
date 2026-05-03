package com.stayloop.domain.rate

import com.stayloop.domain.common.value.Money
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ReservationPriceCalculatorTest {
    private val calculator = ReservationPriceCalculator()
    private val anyRoomTypeId = 1L

    @DisplayName("단일 일자 요금은 그대로 합산 결과가 된다.")
    @Test
    fun shouldSumSingleNight() {
        val rates = listOf(rateOf(date = LocalDate.of(2026, 5, 10), price = 120_000L))

        val total = calculator.totalPrice(rates)

        assertThat(total).isEqualTo(Money.of(120_000L))
    }

    @DisplayName("다중 일자 요금은 각 일자의 가격을 합산한다 — plus 만 사용 (times 미사용).")
    @Test
    fun shouldSumMultipleNights() {
        val rates = listOf(
            rateOf(date = LocalDate.of(2026, 5, 10), price = 100_000L),
            rateOf(date = LocalDate.of(2026, 5, 11), price = 110_000L),
            rateOf(date = LocalDate.of(2026, 5, 12), price = 130_000L),
        )

        val total = calculator.totalPrice(rates)

        assertThat(total).isEqualTo(Money.of(340_000L))
    }

    @DisplayName("일자별 요금이 다를 때 (성수기 / 비수기) 각 일자의 실제 가격을 합산한다.")
    @Test
    fun shouldSumHeterogeneousNights() {
        // 크리스마스 이브 / 크리스마스 / 일반일 — 가격이 다른 3박
        val rates = listOf(
            rateOf(date = LocalDate.of(2026, 12, 24), price = 250_000L),
            rateOf(date = LocalDate.of(2026, 12, 25), price = 300_000L),
            rateOf(date = LocalDate.of(2026, 12, 26), price = 200_000L),
        )

        val total = calculator.totalPrice(rates)

        assertThat(total).isEqualTo(Money.of(750_000L))
    }

    @DisplayName("빈 리스트 합산은 BAD_REQUEST 로 거절된다 — 0박 예약 차단.")
    @Test
    fun shouldReject_whenRatesEmpty() {
        assertThatThrownBy { calculator.totalPrice(emptyList()) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("0원 요금이 섞여 있어도 합산이 가능하다 — 무료 객실 / 프로모션 시나리오 자리.")
    @Test
    fun shouldHandleZeroPriceMixedWithPositive() {
        val rates = listOf(
            rateOf(date = LocalDate.of(2026, 5, 10), price = 0L),
            rateOf(date = LocalDate.of(2026, 5, 11), price = 100_000L),
        )

        val total = calculator.totalPrice(rates)

        assertThat(total).isEqualTo(Money.of(100_000L))
    }

    private fun rateOf(date: LocalDate, price: Long): DailyRoomRateModel =
        DailyRoomRateModel.create(roomTypeId = anyRoomTypeId, date = date, pricePerNight = Money.of(price))
}
