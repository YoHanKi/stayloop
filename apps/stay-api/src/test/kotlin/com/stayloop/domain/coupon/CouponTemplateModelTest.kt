package com.stayloop.domain.coupon

import com.stayloop.domain.coupon.value.DiscountType
import com.stayloop.domain.coupon.value.DiscountValue
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CouponTemplateModelTest {
    private fun discount() = DiscountValue.of(DiscountType.FIXED, 1_000)

    @DisplayName("remaining 은 한정 수량에서 발급 수를 뺀 값이다.")
    @Test
    fun remaining() {
        val template = CouponTemplateModel("선착순", discount(), totalQuantity = 10, issuedCount = 3)
        assertThat(template.remaining()).isEqualTo(7)
    }

    @DisplayName("한정 수량이 0 이하면 BAD_REQUEST 로 거절된다.")
    @Test
    fun rejectNonPositiveQuantity() {
        assertThatThrownBy { CouponTemplateModel("선착순", discount(), totalQuantity = 0) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("발급 수가 한정 수량을 넘는 상태로는 생성할 수 없다.")
    @Test
    fun rejectIssuedOverQuantity() {
        assertThatThrownBy { CouponTemplateModel("선착순", discount(), totalQuantity = 10, issuedCount = 11) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
