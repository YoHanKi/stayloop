package com.stayloop.domain.coupon.value

import com.stayloop.domain.common.value.Money
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class DiscountTest {

    @DisplayName("정상 생성 시 beforeDiscount / amount / finalPrice 가 그대로 노출된다.")
    @Test
    fun shouldExposeFields() {
        val discount =
            Discount(
                beforeDiscount = Money.of(10_000),
                amount = Money.of(3_000),
                finalPrice = Money.of(7_000),
            )

        assertThat(discount.beforeDiscount).isEqualTo(Money.of(10_000))
        assertThat(discount.amount).isEqualTo(Money.of(3_000))
        assertThat(discount.finalPrice).isEqualTo(Money.of(7_000))
    }

    @DisplayName("할인 금액이 결제 금액을 초과하면 BAD_REQUEST 로 거절된다 — 환급 형태 차단.")
    @Test
    fun shouldReject_whenAmountExceedsBeforeDiscount() {
        assertThatThrownBy {
            Discount(
                beforeDiscount = Money.of(9_000),
                amount = Money.of(10_000),
                finalPrice = Money.ZERO,
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("산술이 일치하지 않으면 (finalPrice ≠ beforeDiscount - amount) BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenArithmeticMismatches() {
        assertThatThrownBy {
            Discount(
                beforeDiscount = Money.of(10_000),
                amount = Money.of(3_000),
                finalPrice = Money.of(8_000),
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("finalPrice == 0 인 정확히 일치하는 정액 할인은 허용된다 (완전 무료 promo).")
    @Test
    fun shouldAllow_whenFinalPriceIsZero() {
        val discount =
            Discount(
                beforeDiscount = Money.of(9_000),
                amount = Money.of(9_000),
                finalPrice = Money.ZERO,
            )

        assertThat(discount.finalPrice).isEqualTo(Money.ZERO)
        assertThat(discount.amount).isEqualTo(Money.of(9_000))
    }

    @DisplayName("Discount.none(beforeDiscount) 는 amount = ZERO, finalPrice = beforeDiscount 인 쿠폰 미적용 케이스를 만든다.")
    @Test
    fun shouldCreateNone_whenCouponNotApplied() {
        val before = Money.of(10_000)

        val discount = Discount.none(before)

        assertThat(discount.beforeDiscount).isEqualTo(before)
        assertThat(discount.amount).isEqualTo(Money.ZERO)
        assertThat(discount.finalPrice).isEqualTo(before)
    }
}
