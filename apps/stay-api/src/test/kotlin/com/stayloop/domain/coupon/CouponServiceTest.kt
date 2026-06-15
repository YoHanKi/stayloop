package com.stayloop.domain.coupon

import com.stayloop.domain.coupon.value.CouponStatus
import com.stayloop.domain.coupon.value.DiscountType
import com.stayloop.domain.coupon.value.DiscountValue
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import com.stayloop.support.test.InMemoryCouponTemplateRepository
import com.stayloop.support.test.InMemoryIssuedCouponRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class CouponServiceTest {
    private lateinit var templateRepository: InMemoryCouponTemplateRepository
    private lateinit var issuedRepository: InMemoryIssuedCouponRepository
    private lateinit var service: CouponService

    private val alice = LoginId("alice01")
    private val bob = LoginId("bobby02")
    private val now = LocalDateTime.of(2026, 6, 1, 10, 0)

    @BeforeEach
    fun setUp() {
        templateRepository = InMemoryCouponTemplateRepository()
        issuedRepository = InMemoryIssuedCouponRepository()
        service = CouponService(templateRepository, issuedRepository)
    }

    private fun template(total: Int = 5) =
        templateRepository.save(CouponTemplateModel("선착순", DiscountValue.of(DiscountType.FIXED, 1_000), total))

    @DisplayName("발급은 발급 수를 1 늘리고 할인을 스냅샷한 AVAILABLE 쿠폰을 만든다.")
    @Test
    fun issue() {
        val templateId = template(total = 5).id

        val coupon = service.issue(templateId, alice)

        assertThat(coupon.status).isEqualTo(CouponStatus.AVAILABLE)
        assertThat(coupon.discount.amount).isEqualTo(1_000)
        assertThat(templateRepository.findById(templateId)!!.issuedCount).isEqualTo(1)
    }

    @DisplayName("소진된 쿠폰을 발급하면 CONFLICT 로 거절된다.")
    @Test
    fun rejectWhenSoldOut() {
        val templateId = template(total = 1).id
        service.issue(templateId, alice)

        assertThatThrownBy { service.issue(templateId, bob) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("존재하지 않는 쿠폰을 발급하면 NOT_FOUND 로 거절된다.")
    @Test
    fun rejectWhenTemplateNotFound() {
        assertThatThrownBy { service.issue(999L, alice) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND)
    }

    @DisplayName("사용은 AVAILABLE 쿠폰을 USED 로 바꾼다.")
    @Test
    fun use() {
        val coupon = service.issue(template().id, alice)

        service.use(coupon.id, now)

        assertThat(issuedRepository.findById(coupon.id)!!.status).isEqualTo(CouponStatus.USED)
    }

    @DisplayName("이미 사용한 쿠폰을 다시 사용하면 CONFLICT 로 거절된다.")
    @Test
    fun rejectWhenAlreadyUsed() {
        val coupon = service.issue(template().id, alice)
        service.use(coupon.id, now)

        assertThatThrownBy { service.use(coupon.id, now) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }
}
