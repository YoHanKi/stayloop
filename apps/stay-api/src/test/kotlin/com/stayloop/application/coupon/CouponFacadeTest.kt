package com.stayloop.application.coupon

import com.stayloop.application.coupon.command.CreateCouponTemplateCommand
import com.stayloop.application.coupon.command.IssueCouponCommand
import com.stayloop.domain.coupon.CouponService
import com.stayloop.domain.coupon.value.DiscountType
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

class CouponFacadeTest {
    private lateinit var templateRepository: InMemoryCouponTemplateRepository
    private lateinit var issuedRepository: InMemoryIssuedCouponRepository
    private lateinit var sut: CouponFacade

    private val alice = LoginId("alice01")
    private val bob = LoginId("bobby02")

    @BeforeEach
    fun setUp() {
        templateRepository = InMemoryCouponTemplateRepository()
        issuedRepository = InMemoryIssuedCouponRepository()
        sut = CouponFacade(templateRepository, issuedRepository, CouponService(templateRepository, issuedRepository))
    }

    private fun create(total: Int = 10): Long =
        sut.createTemplate(CreateCouponTemplateCommand("선착순", DiscountType.FIXED, 5_000, total)).templateId

    @DisplayName("템플릿 생성은 발급 수 0 으로 시작하고 잔여가 한정 수량과 같다.")
    @Test
    fun createTemplate() {
        val info = sut.createTemplate(CreateCouponTemplateCommand("선착순", DiscountType.PERCENT, 10, 100))

        assertThat(info.issuedCount).isEqualTo(0)
        assertThat(info.remaining).isEqualTo(100)
        assertThat(info.discountType).isEqualTo("PERCENT")
    }

    @DisplayName("발급은 발급 쿠폰을 반환하고 발급 수를 늘린다.")
    @Test
    fun issue() {
        val templateId = create(total = 10)

        val info = sut.issue(IssueCouponCommand(alice, templateId))

        assertThat(info.status).isEqualTo("AVAILABLE")
        assertThat(templateRepository.findById(templateId)!!.issuedCount).isEqualTo(1)
    }

    @DisplayName("동일인이 같은 쿠폰을 다시 발급받으면 CONFLICT 로 거절된다(중복 발급).")
    @Test
    fun rejectDuplicateIssue() {
        val templateId = create(total = 10)
        sut.issue(IssueCouponCommand(alice, templateId))

        assertThatThrownBy { sut.issue(IssueCouponCommand(alice, templateId)) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("소진된 쿠폰 발급은 CONFLICT 로 거절된다.")
    @Test
    fun rejectWhenSoldOut() {
        val templateId = create(total = 1)
        sut.issue(IssueCouponCommand(alice, templateId))

        assertThatThrownBy { sut.issue(IssueCouponCommand(bob, templateId)) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("내 쿠폰 목록을 조회한다.")
    @Test
    fun getMyCoupons() {
        sut.issue(IssueCouponCommand(alice, create(total = 10)))
        sut.issue(IssueCouponCommand(alice, create(total = 10)))

        assertThat(sut.getMyCoupons(alice, 0, 20)).hasSize(2)
    }
}
