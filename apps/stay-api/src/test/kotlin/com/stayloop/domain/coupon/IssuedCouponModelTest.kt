package com.stayloop.domain.coupon

import com.stayloop.domain.coupon.value.CouponStatus
import com.stayloop.domain.coupon.value.DiscountType
import com.stayloop.domain.coupon.value.DiscountValue
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class IssuedCouponModelTest {
    private fun issued() =
        IssuedCouponModel(
            couponTemplateId = 1L,
            userId = LoginId("user01"),
            discount = DiscountValue.of(DiscountType.FIXED, 1_000),
        )

    @DisplayName("use 는 AVAILABLE 쿠폰을 USED 로 바꾸고 사용 시각을 남긴다.")
    @Test
    fun use() {
        val coupon = issued()
        val now = LocalDateTime.of(2026, 6, 1, 10, 0)

        coupon.use(now)

        assertThat(coupon.status).isEqualTo(CouponStatus.USED)
        assertThat(coupon.usedAt).isEqualTo(now)
    }

    @DisplayName("이미 사용한 쿠폰을 다시 사용하면 CONFLICT 로 거절된다.")
    @Test
    fun rejectReuse() {
        val coupon = issued()
        coupon.use(LocalDateTime.of(2026, 6, 1, 10, 0))

        assertThatThrownBy { coupon.use(LocalDateTime.of(2026, 6, 2, 10, 0)) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }
}
