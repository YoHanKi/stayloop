package com.stayloop.support.test

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.common.value.SortKey
import com.stayloop.domain.coupon.CouponIssueModel
import com.stayloop.domain.user.UserModel
import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.Name
import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class InMemoryCouponIssueRepositoryTest {

    private val encoder = FakePasswordEncoder()
    private val users = InMemoryUserRepository()
    private lateinit var repository: InMemoryCouponIssueRepository

    private val ownerLogin = LoginId("alpha01")
    private val otherLogin = LoginId("bravo02")
    private val unknownLogin = LoginId("ghost99")
    private var ownerId = 0L
    private var otherId = 0L

    @BeforeEach
    fun setUp() {
        users.save(userOf(ownerLogin)).also { ownerId = it.id }
        users.save(userOf(otherLogin)).also { otherId = it.id }
        repository = InMemoryCouponIssueRepository(users)
    }

    private fun userOf(loginId: LoginId): UserModel = UserModel.create(
        loginId = loginId,
        rawPassword = "Abcd1234!",
        name = Name("홍길동"),
        birthDate = BirthDate(LocalDate.of(2000, 1, 1)),
        email = Email("alen@stayloop.io"),
        phoneNumber = PhoneNumber("010-1234-5678"),
        encoder = encoder,
    )

    private fun issue(userId: Long, issuedAt: LocalDateTime) =
        CouponIssueModel.issue(templateId = 1L, userId = userId, issuedAt = issuedAt)

    @DisplayName("save 시 신규 엔티티에 순차 id 가 할당되어 findById 로 재조회된다.")
    @Test
    fun shouldAssignIdAndPersist() {
        val saved = repository.save(issue(ownerId, LocalDateTime.of(2026, 5, 1, 10, 0)))

        assertThat(saved.id).isPositive()
        assertThat(repository.findById(saved.id)).isEqualTo(saved)
    }

    @DisplayName("findByUserId 는 boundary 에서 LoginId 를 받아 BIGINT 변환 후 본인 발급분만 issuedAt DESC, id DESC 순으로 반환한다.")
    @Test
    fun shouldOrderByIssuedAtDescThenIdDesc() {
        val older = repository.save(issue(ownerId, LocalDateTime.of(2026, 5, 1, 10, 0)))
        val newer = repository.save(issue(ownerId, LocalDateTime.of(2026, 5, 5, 10, 0)))
        repository.save(issue(otherId, LocalDateTime.of(2026, 5, 3, 10, 0)))

        val result = repository.findByUserId(ownerLogin, PageQuery(page = 0, size = 10))

        assertThat(result).containsExactly(newer, older)
    }

    @DisplayName("findByUserId 는 page.offset / limit 으로 페이지네이션한다.")
    @Test
    fun shouldPaginate() {
        repeat(5) { i ->
            repository.save(issue(ownerId, LocalDateTime.of(2026, 5, 1, 10, 0).plusDays(i.toLong())))
        }

        val firstPage = repository.findByUserId(ownerLogin, PageQuery(page = 0, size = 2))
        val secondPage = repository.findByUserId(ownerLogin, PageQuery(page = 1, size = 2))

        assertThat(firstPage).hasSize(2)
        assertThat(secondPage).hasSize(2)
        assertThat(firstPage).doesNotContainAnyElementsOf(secondPage)
    }

    @DisplayName("findByUserId 는 page.sort 가 비어있지 않으면 BAD_REQUEST — silent ignore 차단.")
    @Test
    fun shouldReject_whenSortProvided() {
        val pageWithSort = PageQuery(
            page = 0,
            size = 10,
            sort = listOf(SortKey(property = "issuedAt")),
        )

        assertThatThrownBy { repository.findByUserId(ownerLogin, pageWithSort) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("findByUserId 는 매핑되는 사용자가 없으면 빈 리스트 (silent empty) — Wishlist 동일 정책.")
    @Test
    fun shouldReturnEmpty_whenUserNotFound() {
        repository.save(issue(ownerId, LocalDateTime.of(2026, 5, 1, 10, 0)))

        val result = repository.findByUserId(unknownLogin, PageQuery(page = 0, size = 10))

        assertThat(result).isEmpty()
    }
}
