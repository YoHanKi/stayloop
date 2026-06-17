package com.stayloop.domain.reservation

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.reservation.value.ReservationStatus
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import com.stayloop.support.test.ReservationFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReservationServiceTest {
    private val service = ReservationService(ReservationPriceCalculator())

    private val day1 = LocalDate.of(2026, 6, 1)
    private val day2 = LocalDate.of(2026, 6, 2)

    private fun reserve(
        guestCount: Int = 2,
        rates: List<com.stayloop.domain.rate.DailyRoomRateModel> =
            listOf(ReservationFixture.rate(day1, 100_000), ReservationFixture.rate(day2, 120_000)),
        discount: com.stayloop.domain.coupon.value.DiscountValue? = null,
        couponId: Long? = null,
    ) = service.reserve(
        userId = ReservationFixture.USER,
        property = ReservationFixture.property(),
        roomType = ReservationFixture.roomType(),
        period = ReservationFixture.period(),
        guestCount = guestCount,
        guest = ReservationFixture.GUEST,
        rates = rates,
        discount = discount,
        couponId = couponId,
        idempotencyKey = null,
    )

    @DisplayName("정상 예약은 PENDING 으로 생성되고 할인 없으면 할인 전·최종 금액이 합산 요금과 같다.")
    @Test
    fun shouldReserveNormally() {
        val reservation = reserve()

        assertThat(reservation.status).isEqualTo(ReservationStatus.PENDING)
        assertThat(reservation.priceBeforeDiscount).isEqualTo(Money.of(220_000))
        assertThat(reservation.discountAmount).isEqualTo(Money.ZERO)
        assertThat(reservation.totalPrice).isEqualTo(Money.of(220_000))
        assertThat(reservation.couponId).isNull()
    }

    @DisplayName("쿠폰 할인이 있으면 할인액을 빼 최종 금액을 매기고 금액 3종·쿠폰 식별자를 스냅샷한다.")
    @Test
    fun shouldApplyCouponDiscount() {
        val reservation = reserve(
            discount = com.stayloop.domain.coupon.value.DiscountValue.of(
                com.stayloop.domain.coupon.value.DiscountType.FIXED,
                20_000,
            ),
            couponId = 7L,
        )

        assertThat(reservation.priceBeforeDiscount).isEqualTo(Money.of(220_000))
        assertThat(reservation.discountAmount).isEqualTo(Money.of(20_000))
        assertThat(reservation.totalPrice).isEqualTo(Money.of(200_000))
        assertThat(reservation.couponId).isEqualTo(7L)
    }

    @DisplayName("요청 인원이 객실 최대 인원을 넘으면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenGuestExceedsMax() {
        assertThatThrownBy { reserve(guestCount = 5) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("요금이 투숙 일자와 1:1 로 맞지 않으면(누락) BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenDatesMismatch() {
        val rates = listOf(ReservationFixture.rate(day1, 100_000))

        assertThatThrownBy { reserve(rates = rates) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("취소는 예약을 CANCELLED 로 바꾼다(재고 복원은 reserver 의 몫).")
    @Test
    fun shouldCancel() {
        val reservation = ReservationFixture.reservation()

        service.cancel(reservation, LocalDateTime.of(2026, 5, 30, 9, 0))

        assertThat(reservation.status).isEqualTo(ReservationStatus.CANCELLED)
    }

    @DisplayName("CHECKED_IN 이후 취소는 CONFLICT 로 거절된다.")
    @Test
    fun shouldReject_whenCancelAfterCheckIn() {
        val reservation = ReservationFixture.reservation()
        reservation.confirm()
        reservation.checkIn()

        assertThatThrownBy { service.cancel(reservation, LocalDateTime.now()) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }
}
