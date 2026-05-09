package com.stayloop.application.coupon.admin

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.coupon.CouponIssueModel
import com.stayloop.domain.coupon.CouponTemplateModel
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

class CouponAdminFacadeTest {
    private lateinit var users: InMemoryUserRepository
    private lateinit var templates: InMemoryCouponTemplateRepository
    private lateinit var issues: InMemoryCouponIssueRepository
    private lateinit var sut: CouponAdminFacade

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
        sut = CouponAdminFacade(
            couponTemplateRepository = templates,
            couponIssueRepository = issues,
            userRepository = users,
            clock = fixedClock,
        )
    }

    @DisplayName("register 는 신규 코드를 등록하고 templateId 를 부여한다.")
    @Test
    fun shouldRegisterNewTemplate() {
        val info = sut.register(
            RegisterCouponTemplateCommand(
                code = "SUMMER10",
                name = "여름 휴가 10% 할인",
                discountType = DiscountType.RATE,
                discountValue = 10L,
                minOrderAmount = Money.of(100_000L),
                expiredAt = now.plusDays(30),
            ),
        )

        assertThat(info.templateId).isPositive()
        assertThat(info.code).isEqualTo("SUMMER10")
        assertThat(info.discountType).isEqualTo(DiscountType.RATE)
        assertThat(info.discountValue).isEqualTo(10L)
    }

    @DisplayName("register 는 이미 등록된 code 에 대해 CONFLICT 로 거절한다.")
    @Test
    fun shouldRejectDuplicateCode() {
        saveTemplate(code = "SAME")

        assertThatThrownBy {
            sut.register(
                RegisterCouponTemplateCommand(
                    code = "SAME",
                    name = "다른 이름",
                    discountType = DiscountType.FIXED,
                    discountValue = 5_000L,
                    minOrderAmount = null,
                    expiredAt = now.plusDays(7),
                ),
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("update 는 자기 자신의 code 유지 시 통과하고, 다른 row 의 code 면 CONFLICT 다.")
    @Test
    fun shouldRejectUpdateThatCollidesWithOtherRow() {
        val a = saveTemplate(code = "AAA")
        saveTemplate(code = "BBB")

        // 자기 자신의 code 유지 → OK
        sut.update(
            UpdateCouponTemplateCommand(
                templateId = a.id,
                code = "AAA",
                name = "변경",
                discountType = DiscountType.RATE,
                discountValue = 20L,
                minOrderAmount = null,
                expiredAt = now.plusDays(7),
            ),
        )

        // 다른 row 의 code 로 변경 시도 → CONFLICT
        assertThatThrownBy {
            sut.update(
                UpdateCouponTemplateCommand(
                    templateId = a.id,
                    code = "BBB",
                    name = "충돌",
                    discountType = DiscountType.RATE,
                    discountValue = 20L,
                    minOrderAmount = null,
                    expiredAt = now.plusDays(7),
                ),
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("delete 는 발급 이력이 없는 템플릿을 hard delete 한다.")
    @Test
    fun shouldHardDeleteWhenNoIssues() {
        val template = saveTemplate(code = "TODELETE")

        sut.delete(template.id)

        assertThat(templates.findById(template.id)).isNull()
    }

    @DisplayName("delete 는 발급 이력이 있는 템플릿에 대해 CONFLICT 로 거절한다.")
    @Test
    fun shouldRejectDeleteWhenIssuesExist() {
        val template = saveTemplate(code = "USED")
        val user = saveUser("alen01")
        issues.save(CouponIssueModel.issue(template.id, user.id, now))

        assertThatThrownBy { sut.delete(template.id) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)

        // 거절 후에도 행은 살아 있어야 한다 (회귀 가드)
        assertThat(templates.findById(template.id)).isNotNull
    }

    @DisplayName("list 는 id DESC 순으로 페이지를 반환한다.")
    @Test
    fun shouldListByIdDesc() {
        val first = saveTemplate(code = "AAA")
        val second = saveTemplate(code = "BBB")

        val list = sut.list(PageQuery(0, 20))

        assertThat(list.map { it.templateId }).containsExactly(second.id, first.id)
    }

    @DisplayName("detail 은 존재하지 않는 templateId 에 대해 NOT_FOUND 를 던진다.")
    @Test
    fun shouldThrowNotFoundOnUnknownDetail() {
        assertThatThrownBy { sut.detail(9_999L) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND)
    }

    @DisplayName("listIssues 는 템플릿의 발급 이력을 issuedAt DESC 로 반환하며 사용자 LoginId 를 채운다.")
    @Test
    fun shouldListIssuesWithUserLoginIds() {
        val template = saveTemplate(code = "MULTI")
        val alen = saveUser("alen01")
        val others = saveUser("other02")
        issues.save(CouponIssueModel.issue(template.id, alen.id, now.minusHours(2)))
        issues.save(CouponIssueModel.issue(template.id, others.id, now.minusHours(1)))

        val list = sut.listIssues(template.id, PageQuery(0, 20))

        assertThat(list).hasSize(2)
        assertThat(list[0].userLoginId).isEqualTo("other02") // newer first
        assertThat(list[1].userLoginId).isEqualTo("alen01")
    }

    @DisplayName("listIssues 는 사용자 영속이 누락되면 placeholder 로 표현한다 (어드민 운영 도구는 깨진 데이터도 보여줘야 함).")
    @Test
    fun shouldRepresentMissingUserAsPlaceholder() {
        val template = saveTemplate(code = "ORPHAN")
        // userId = 9999 인 사용자는 등록하지 않는다 — 사용자 hard delete + 발급 잔존 시뮬레이션.
        issues.save(CouponIssueModel.issue(template.id, 9_999L, now))

        val list = sut.listIssues(template.id, PageQuery(0, 20))

        assertThat(list).hasSize(1)
        assertThat(list[0].userLoginId).isEqualTo("<unknown>")
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
        expiredAt: LocalDateTime = now.plusDays(30),
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
