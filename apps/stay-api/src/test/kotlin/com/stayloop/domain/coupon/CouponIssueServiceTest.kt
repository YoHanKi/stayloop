package com.stayloop.domain.coupon

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.coupon.value.CouponName
import com.stayloop.domain.coupon.value.DiscountType
import com.stayloop.domain.coupon.value.DiscountValue
import com.stayloop.domain.coupon.value.ExpirationPeriod
import com.stayloop.domain.coupon.value.MinOrderAmount
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Field
import java.time.LocalDateTime

class CouponIssueServiceTest {

    private val service = CouponIssueService()

    private val expiredAt = LocalDateTime.of(2026, 6, 30, 23, 59, 59)
    private val now = LocalDateTime.of(2026, 5, 8, 14, 0)

    private fun fixedTemplate(amount: Long = 3_000L, minOrder: Money? = null): CouponTemplateModel =
        CouponTemplateModel.create(
            code = "FIXED3000",
            name = CouponName(value = "3천원 할인"),
            discountValue = DiscountValue(type = DiscountType.FIXED, rawValue = amount),
            expirationPeriod = ExpirationPeriod(expiredAt = expiredAt),
            minOrderAmount = minOrder?.let { MinOrderAmount(value = it) },
        ).withId(1L)

    private fun rateTemplate(percent: Long = 10L): CouponTemplateModel =
        CouponTemplateModel.create(
            code = "RATE10",
            name = CouponName(value = "10% 할인"),
            discountValue = DiscountValue(type = DiscountType.RATE, rawValue = percent),
            expirationPeriod = ExpirationPeriod(expiredAt = expiredAt),
        ).withId(1L)

    private fun expiredTemplate(): CouponTemplateModel =
        CouponTemplateModel.create(
            code = "EXPIRED",
            name = CouponName(value = "만료된 쿠폰"),
            discountValue = DiscountValue(type = DiscountType.FIXED, rawValue = 1_000L),
            expirationPeriod = ExpirationPeriod(expiredAt = now.minusSeconds(1)),
        ).withId(1L)

    private fun issueOf(templateId: Long, userId: Long = 1L): CouponIssueModel =
        CouponIssueModel.issue(templateId = templateId, userId = userId, issuedAt = now.minusDays(1))

    @DisplayName("FIXED 정액 정상 — Discount(before=10000, amount=3000, final=7000) 반환.")
    @Test
    fun shouldApplyFixed() {
        val template = fixedTemplate(amount = 3_000L)
        val issue = issueOf(templateId = template.id)

        val discount = service.apply(issue, template, beforeDiscount = Money.of(10_000), now = now)

        assertThat(discount.beforeDiscount).isEqualTo(Money.of(10_000))
        assertThat(discount.amount).isEqualTo(Money.of(3_000))
        assertThat(discount.finalPrice).isEqualTo(Money.of(7_000))
    }

    @DisplayName("RATE 10% 정상 — Discount(before=10000, amount=1000, final=9000) 반환.")
    @Test
    fun shouldApplyRate() {
        val template = rateTemplate(percent = 10L)
        val issue = issueOf(templateId = template.id)

        val discount = service.apply(issue, template, beforeDiscount = Money.of(10_000), now = now)

        assertThat(discount.amount).isEqualTo(Money.of(1_000))
        assertThat(discount.finalPrice).isEqualTo(Money.of(9_000))
    }

    @DisplayName("FIXED 가 결제금액 초과 시 cap — DiscountValue.apply 의 cap 정책이 finalPrice = 0 으로 떨어진다.")
    @Test
    fun shouldCapFixed_whenExceedsBeforeDiscount() {
        val template = fixedTemplate(amount = 10_000L)
        val issue = issueOf(templateId = template.id)

        val discount = service.apply(issue, template, beforeDiscount = Money.of(9_000), now = now)

        assertThat(discount.amount).isEqualTo(Money.of(9_000))
        assertThat(discount.finalPrice).isEqualTo(Money.ZERO)
    }

    @DisplayName("issue.templateId 가 template.id 와 일치하지 않으면 BAD_REQUEST — silent 다른 정책 적용 차단.")
    @Test
    fun shouldReject_whenIssueTemplateMismatch() {
        val template = fixedTemplate().withId(1L)
        val issue = issueOf(templateId = 99L) // 다른 templateId

        assertThatThrownBy {
            service.apply(issue, template, beforeDiscount = Money.of(10_000), now = now)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("템플릿이 만료되었으면 BAD_REQUEST.")
    @Test
    fun shouldReject_whenTemplateExpired() {
        val template = expiredTemplate()
        val issue = issueOf(templateId = template.id)

        assertThatThrownBy {
            service.apply(issue, template, beforeDiscount = Money.of(10_000), now = now)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("최소 결제 금액 미달이면 BAD_REQUEST — DiscountValue.apply 호출 전 차단.")
    @Test
    fun shouldReject_whenBelowMinOrderAmount() {
        val template = fixedTemplate(amount = 3_000L, minOrder = Money.of(50_000))
        val issue = issueOf(templateId = template.id)

        assertThatThrownBy {
            service.apply(issue, template, beforeDiscount = Money.of(10_000), now = now)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("minOrderAmount 가 null 이면 결제금액에 관계없이 통과.")
    @Test
    fun shouldPass_whenMinOrderAmountIsNull() {
        val template = fixedTemplate(amount = 1_000L, minOrder = null)
        val issue = issueOf(templateId = template.id)

        val discount = service.apply(issue, template, beforeDiscount = Money.of(100), now = now)

        assertThat(discount.amount).isEqualTo(Money.of(100)) // cap to before
        assertThat(discount.finalPrice).isEqualTo(Money.ZERO)
    }

    @DisplayName("apply 는 issue 의 상태를 변경하지 않는다 — Facade 가 별도로 issue.use 를 호출해야 한다.")
    @Test
    fun shouldNotMutateIssue() {
        val template = fixedTemplate()
        val issue = issueOf(templateId = template.id)
        val statusBefore = issue.status
        val usedAtBefore = issue.usedAt
        val usedReservationIdBefore = issue.usedReservationId

        service.apply(issue, template, beforeDiscount = Money.of(10_000), now = now)

        assertThat(issue.status).isEqualTo(statusBefore)
        assertThat(issue.usedAt).isEqualTo(usedAtBefore)
        assertThat(issue.usedReservationId).isEqualTo(usedReservationIdBefore)
    }
}

/**
 * 테스트 전용 — `BaseEntity.id` 를 reflection 으로 강제 할당. 도메인 서비스 단위 테스트에서 *영속화 없이*
 * id 가 필요한 케이스 (issue.templateId vs template.id 검증) 의 fake 헬퍼. InMemory 더블 패턴 답습.
 */
private fun <T : Any> T.withId(id: Long): T {
    val field: Field = com.stayloop.domain.BaseEntity::class.java.getDeclaredField("id")
    field.isAccessible = true
    field.setLong(this, id)
    return this
}
