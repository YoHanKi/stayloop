package com.stayloop.domain.reservation.value

import com.stayloop.domain.coupon.value.DiscountType
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CouponSnapshotTest {

    @DisplayName("정상 생성 시 박제된 couponId / couponName / couponCode / discountType 가 그대로 노출된다.")
    @Test
    fun shouldExposeFields() {
        val snapshot = CouponSnapshot(
            couponId = 42L,
            couponName = "여름 휴가 시즌 10% 할인",
            couponCode = "SUMMER10",
            discountType = DiscountType.RATE,
        )

        assertThat(snapshot.couponId).isEqualTo(42L)
        assertThat(snapshot.couponName).isEqualTo("여름 휴가 시즌 10% 할인")
        assertThat(snapshot.couponCode).isEqualTo("SUMMER10")
        assertThat(snapshot.discountType).isEqualTo(DiscountType.RATE)
    }

    @DisplayName("couponId 가 0 이하면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(longs = [0L, -1L, -100L])
    fun shouldReject_whenCouponIdIsZeroOrNegative(couponId: Long) {
        assertThatThrownBy {
            CouponSnapshot(
                couponId = couponId,
                couponName = "이름",
                couponCode = "CODE",
                discountType = DiscountType.FIXED,
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("couponName 이 빈 문자열이거나 MAX_NAME_LENGTH(100) 초과면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenCouponNameViolatesGuard() {
        assertThatThrownBy {
            CouponSnapshot(couponId = 1L, couponName = "", couponCode = "C", discountType = DiscountType.FIXED)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)

        val tooLong = "가".repeat(CouponSnapshot.MAX_NAME_LENGTH + 1)
        assertThatThrownBy {
            CouponSnapshot(couponId = 1L, couponName = tooLong, couponCode = "C", discountType = DiscountType.FIXED)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("couponCode 가 빈 문자열이거나 MAX_CODE_LENGTH(50) 초과면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenCouponCodeViolatesGuard() {
        assertThatThrownBy {
            CouponSnapshot(couponId = 1L, couponName = "이름", couponCode = "", discountType = DiscountType.FIXED)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)

        val tooLong = "A".repeat(CouponSnapshot.MAX_CODE_LENGTH + 1)
        assertThatThrownBy {
            CouponSnapshot(couponId = 1L, couponName = "이름", couponCode = tooLong, discountType = DiscountType.FIXED)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
