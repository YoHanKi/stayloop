package com.stayloop.domain.rate

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.coupon.value.Discount
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

    @DisplayName("discount = null 이면 합산 결과 그대로 반환 — 기존 호출 흐름 회귀.")
    @Test
    fun shouldKeepLegacyBehaviorWhenDiscountIsNull() {
        val rates = listOf(
            rateOf(date = LocalDate.of(2026, 5, 10), price = 100_000L),
            rateOf(date = LocalDate.of(2026, 5, 11), price = 110_000L),
        )

        val total = calculator.totalPrice(rates, discount = null)

        assertThat(total).isEqualTo(Money.of(210_000L))
    }

    @DisplayName("discount 가 주어지면 finalPrice 를 반환한다.")
    @Test
    fun shouldReturnFinalPrice_whenDiscountProvided() {
        val rates = listOf(
            rateOf(date = LocalDate.of(2026, 5, 10), price = 100_000L),
            rateOf(date = LocalDate.of(2026, 5, 11), price = 100_000L),
        )
        val discount = Discount(
            beforeDiscount = Money.of(200_000L),
            amount = Money.of(20_000L),
            finalPrice = Money.of(180_000L),
        )

        val total = calculator.totalPrice(rates, discount)

        assertThat(total).isEqualTo(Money.of(180_000L))
    }

    @DisplayName("discount.beforeDiscount 가 합산 결과와 일치하지 않으면 BAD_REQUEST — 외부 조립 거부.")
    @Test
    fun shouldReject_whenDiscountBeforeMismatchSum() {
        val rates = listOf(
            rateOf(date = LocalDate.of(2026, 5, 10), price = 100_000L),
            rateOf(date = LocalDate.of(2026, 5, 11), price = 100_000L),
        )
        // 합산은 200_000 인데 discount 가 다른 기준 (250_000) 으로 만들어진 경우
        val mismatched = Discount(
            beforeDiscount = Money.of(250_000L),
            amount = Money.of(25_000L),
            finalPrice = Money.of(225_000L),
        )

        assertThatThrownBy { calculator.totalPrice(rates, mismatched) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    private fun rateOf(date: LocalDate, price: Long): DailyRoomRateModel =
        DailyRoomRateModel.create(roomTypeId = anyRoomTypeId, date = date, pricePerNight = Money.of(price))
}
