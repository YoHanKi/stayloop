package com.stayloop.application.coupon

import com.stayloop.application.coupon.command.IssueCouponCommand
import com.stayloop.domain.common.value.Money
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.common.value.SortDirection
import com.stayloop.domain.common.value.SortKey
import com.stayloop.domain.coupon.CouponIssueModel
import com.stayloop.domain.coupon.CouponTemplateModel
import com.stayloop.domain.coupon.value.CouponIssueStatus
import com.stayloop.domain.coupon.value.CouponName
import com.stayloop.domain.coupon.value.DiscountType
import com.stayloop.domain.coupon.value.DiscountValue
import com.stayloop.domain.coupon.value.ExpirationPeriod
import com.stayloop.domain.coupon.value.MinOrderAmount
import com.stayloop.domain.user.UserModel
import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import com.stayloop.support.test.FakePasswordEncoder
import com.stayloop.support.test.InMemoryCouponIssueRepository
import com.stayloop.support.test.InMemoryCouponTemplateRepository
import com.stayloop.support.test.InMemoryUserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import com.stayloop.domain.user.value.Name as UserName

class CouponFacadeTest {
    private lateinit var users: InMemoryUserRepository
    private lateinit var templates: InMemoryCouponTemplateRepository
    private lateinit var issues: InMemoryCouponIssueRepository
    private lateinit var sut: CouponFacade

    private val now: LocalDateTime = LocalDateTime.parse("2026-05-09T10:00:00")
    private val fixedClock: Clock = Clock.fixed(
        now.atZone(ZoneId.of("Asia/Seoul")).toInstant(),
        ZoneId.of("Asia/Seoul"),
    )

    @BeforeEach
    fun setUp() {
        users = InMemoryUserRepository()
        templates = InMemoryCouponTemplateRepository()
        issues = InMemoryCouponIssueRepository(users)
        sut = CouponFacade(
            couponTemplateRepository = templates,
            couponIssueRepository = issues,
            userRepository = users,
            clock = fixedClock,
        )
    }

    @DisplayName("issue 는 만료되지 않은 템플릿을 본인 계정으로 AVAILABLE 상태로 발급한다.")
    @Test
    fun shouldIssueAvailableCoupon() {
        val user = saveUser("alen01")
        val template = saveTemplate(code = "SUMMER10", expiredAt = now.plusDays(30))

        val info = sut.issue(IssueCouponCommand(actor = user.loginId, templateId = template.id))

        assertThat(info.status).isEqualTo(CouponIssueStatus.AVAILABLE)
        assertThat(info.templateId).isEqualTo(template.id)
        assertThat(info.code).isEqualTo("SUMMER10")
        assertThat(info.issuedAt).isEqualTo(now)
        assertThat(issues.findById(info.issueId)).isNotNull
    }

    @DisplayName("issue 는 존재하지 않는 templateId 에 대해 NOT_FOUND 를 던진다.")
    @Test
    fun shouldRejectUnknownTemplate() {
        val user = saveUser("alen01")

        assertThatThrownBy {
            sut.issue(IssueCouponCommand(actor = user.loginId, templateId = 9_999L))
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND)
    }

    @DisplayName("issue 는 만료된 템플릿에 대해 BAD_REQUEST 로 거절한다.")
    @Test
    fun shouldRejectExpiredTemplate() {
        val user = saveUser("alen01")
        val template = saveTemplate(code = "EXPIRED", expiredAt = now.minusSeconds(1))

        assertThatThrownBy {
            sut.issue(IssueCouponCommand(actor = user.loginId, templateId = template.id))
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("issue 는 영속 사용자가 없으면 UNAUTHORIZED 로 거절한다.")
    @Test
    fun shouldRejectUnknownUser() {
        val template = saveTemplate(code = "WELCOME", expiredAt = now.plusDays(7))
        // 사용자 미등록
        assertThatThrownBy {
            sut.issue(IssueCouponCommand(actor = LoginId("ghost01"), templateId = template.id))
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.UNAUTHORIZED)
    }

    @DisplayName("getMyCoupons 는 본인 발급 이력을 issuedAt DESC 순으로 반환한다.")
    @Test
    fun shouldReturnMyCouponsSortedByIssuedAtDesc() {
        val user = saveUser("alen01")
        val templateA = saveTemplate(code = "AAA", expiredAt = now.plusDays(30))
        val templateB = saveTemplate(code = "BBB", expiredAt = now.plusDays(30))
        // issuedAt 명시 차이로 시드 — fixedClock 이라 sut.issue 만 쓰면 동일 시각이 됨
        issues.save(CouponIssueModel.issue(templateA.id, user.id, now.minusHours(2)))
        issues.save(CouponIssueModel.issue(templateB.id, user.id, now.minusHours(1)))

        val list = sut.getMyCoupons(user.loginId, PageQuery(0, 20))

        assertThat(list).hasSize(2)
        assertThat(list[0].code).isEqualTo("BBB") // newer first
        assertThat(list[1].code).isEqualTo("AAA")
    }

    @DisplayName("getMyCoupons 는 다른 사용자의 발급 이력을 노출하지 않는다.")
    @Test
    fun shouldNotExposeOtherUsersIssues() {
        val alen = saveUser("alen01")
        val others = saveUser("other02")
        val template = saveTemplate(code = "OTHERS", expiredAt = now.plusDays(30))
        issues.save(CouponIssueModel.issue(template.id, others.id, now))

        val list = sut.getMyCoupons(alen.loginId, PageQuery(0, 20))

        assertThat(list).isEmpty()
    }

    @DisplayName("getMyCoupons 는 만료된 템플릿의 AVAILABLE 인스턴스를 응답에서 EXPIRED 로 표현한다 (lazy).")
    @Test
    fun shouldRepresentExpiredAsLazyInResponse() {
        val user = saveUser("alen01")
        val template = saveTemplate(code = "EXPIRED", expiredAt = now.minusSeconds(1))
        val issue = issues.save(CouponIssueModel.issue(template.id, user.id, now.minusDays(5)))

        val list = sut.getMyCoupons(user.loginId, PageQuery(0, 20))

        assertThat(list).hasSize(1)
        assertThat(list[0].status).isEqualTo(CouponIssueStatus.EXPIRED)
        // 영속 모델은 변경되지 않았는지 검증 (silent mutation 차단)
        assertThat(issues.findById(issue.id)?.status).isEqualTo(CouponIssueStatus.AVAILABLE)
    }

    @DisplayName("getMyCoupons 는 page.sort 가 비어있지 않으면 BAD_REQUEST 로 거절한다.")
    @Test
    fun shouldRejectNonEmptySortAsBadRequest() {
        val user = saveUser("alen01")

        assertThatThrownBy {
            sut.getMyCoupons(
                actor = user.loginId,
                page = PageQuery(0, 20, listOf(SortKey("issuedAt", SortDirection.ASC))),
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("getMyCoupons 는 참조 Template 가 누락되면 INTERNAL_ERROR 로 명시 실패한다.")
    @Test
    fun shouldFailExplicitlyWhenReferencedTemplateMissing() {
        val user = saveUser("alen01")
        // 존재하지 않는 templateId 9999 로 직접 issue 행 seed (어드민 hard delete 시뮬레이션).
        issues.save(CouponIssueModel.issue(9_999L, user.id, now))

        assertThatThrownBy {
            sut.getMyCoupons(user.loginId, PageQuery(0, 20))
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR)
    }

    private val encoder = FakePasswordEncoder()

    private fun saveUser(loginId: String): UserModel {
        val user = UserModel.create(
            loginId = LoginId(loginId),
            rawPassword = "Abcd1234!",
            name = UserName("홍길동"),
            birthDate = BirthDate(LocalDate.of(2000, 1, 1)),
            email = Email("$loginId@stayloop.io"),
            phoneNumber = PhoneNumber("010-1234-5678"),
            encoder = encoder,
        )
        return users.save(user)
    }

    private fun saveTemplate(
        code: String,
        expiredAt: LocalDateTime,
        discountType: DiscountType = DiscountType.RATE,
        discountValue: Long = 10L,
        minOrderAmount: Long? = 100_000L,
    ): CouponTemplateModel {
        val template = CouponTemplateModel.create(
            code = code,
            name = CouponName("$code 쿠폰"),
            discountValue = DiscountValue(type = discountType, rawValue = discountValue),
            expirationPeriod = ExpirationPeriod(expiredAt = expiredAt),
            minOrderAmount = minOrderAmount?.let { MinOrderAmount(Money.of(it)) },
        )
        return templates.save(template)
    }
}
