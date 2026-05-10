package com.stayloop.domain.coupon

import com.stayloop.domain.coupon.value.CouponName
import com.stayloop.domain.coupon.value.DiscountType
import com.stayloop.domain.coupon.value.DiscountValue
import com.stayloop.domain.coupon.value.ExpirationPeriod
import com.stayloop.domain.coupon.value.MinOrderAmount
import com.stayloop.domain.common.value.Money
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDateTime

class CouponTemplateModelTest {

    private val expiredAt = LocalDateTime.of(2026, 6, 30, 23, 59, 59)
    private val validNow = expiredAt.minusDays(1)

    private fun create(
        code: String = "WELCOME10000",
        minOrderAmount: MinOrderAmount? = null,
    ) = CouponTemplateModel.create(
        code = code,
        name = CouponName(value = "신규가입 1만원 할인"),
        discountValue = DiscountValue(type = DiscountType.FIXED, rawValue = 10_000L),
        expirationPeriod = ExpirationPeriod(expiredAt = expiredAt),
        minOrderAmount = minOrderAmount,
    )

    @DisplayName("정상 생성 시 모든 박제 항목이 그대로 노출되며 minOrderAmount 는 null 허용된다.")
    @Test
    fun shouldExposeFields_andAllowNullMinOrderAmount() {
        val template = create()

        assertThat(template.code).isEqualTo("WELCOME10000")
        assertThat(template.name.value).isEqualTo("신규가입 1만원 할인")
        assertThat(template.discountValue.type).isEqualTo(DiscountType.FIXED)
        assertThat(template.discountValue.rawValue).isEqualTo(10_000L)
        assertThat(template.minOrderAmount).isNull()
        assertThat(template.expirationPeriod.expiredAt).isEqualTo(expiredAt)
    }

    @DisplayName("minOrderAmount 가 주어지면 그대로 박제된다.")
    @Test
    fun shouldHoldMinOrderAmount_whenProvided() {
        val template = create(minOrderAmount = MinOrderAmount(value = Money.of(50_000)))

        assertThat(template.minOrderAmount?.value).isEqualTo(Money.of(50_000))
    }

    @DisplayName("code 가 비공백이 아니거나 MAX_CODE_LENGTH(50) 초과면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(strings = ["", " "])
    fun shouldReject_whenCodeIsBlank(code: String) {
        assertThatThrownBy { create(code = code) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("code 가 MAX_CODE_LENGTH 를 초과하면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenCodeExceedsMaxLength() {
        val tooLong = "A".repeat(CouponTemplateModel.MAX_CODE_LENGTH + 1)

        assertThatThrownBy { create(code = tooLong) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("now 가 expiredAt 이전이면 isExpired = false, requireUsable 통과.")
    @Test
    fun shouldNotExpired_whenBeforeExpiry() {
        val template = create()

        assertThat(template.isExpired(validNow)).isFalse()
        assertThatCode { template.requireUsable(validNow) }.doesNotThrowAnyException()
    }

    @DisplayName("now 가 expiredAt 이후면 isExpired = true, requireUsable BAD_REQUEST.")
    @Test
    fun shouldExpired_whenAfterExpiry() {
        val template = create()
        val after = expiredAt.plusSeconds(1)

        assertThat(template.isExpired(after)).isTrue()
        assertThatThrownBy { template.requireUsable(after) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
