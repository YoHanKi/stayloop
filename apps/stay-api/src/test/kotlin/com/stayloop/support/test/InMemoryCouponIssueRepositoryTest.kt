package com.stayloop.support.test

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.common.value.SortKey
import com.stayloop.domain.coupon.CouponIssueModel
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class InMemoryCouponIssueRepositoryTest {

    private val owner = LoginId("hong")
    private val other = LoginId("other")
    private lateinit var repository: InMemoryCouponIssueRepository

    @BeforeEach
    fun setUp() {
        repository = InMemoryCouponIssueRepository()
    }

    private fun issue(userId: LoginId, issuedAt: LocalDateTime) =
        CouponIssueModel.issue(templateId = 1L, userId = userId, issuedAt = issuedAt)

    @DisplayName("save 시 신규 엔티티에 순차 id 가 할당되어 findById 로 재조회된다.")
    @Test
    fun shouldAssignIdAndPersist() {
        val saved = repository.save(issue(owner, LocalDateTime.of(2026, 5, 1, 10, 0)))

        assertThat(saved.id).isPositive()
        assertThat(repository.findById(saved.id)).isEqualTo(saved)
    }

    @DisplayName("findByUserId 는 issuedAt DESC, id DESC 순으로 본인 발급분만 반환한다.")
    @Test
    fun shouldOrderByIssuedAtDescThenIdDesc() {
        val older = repository.save(issue(owner, LocalDateTime.of(2026, 5, 1, 10, 0)))
        val newer = repository.save(issue(owner, LocalDateTime.of(2026, 5, 5, 10, 0)))
        repository.save(issue(other, LocalDateTime.of(2026, 5, 3, 10, 0)))

        val result = repository.findByUserId(owner, PageQuery(page = 0, size = 10))

        assertThat(result).containsExactly(newer, older)
    }

    @DisplayName("findByUserId 는 page.offset / limit 으로 페이지네이션한다.")
    @Test
    fun shouldPaginate() {
        repeat(5) { i ->
            repository.save(issue(owner, LocalDateTime.of(2026, 5, 1, 10, 0).plusDays(i.toLong())))
        }

        val firstPage = repository.findByUserId(owner, PageQuery(page = 0, size = 2))
        val secondPage = repository.findByUserId(owner, PageQuery(page = 1, size = 2))

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

        assertThatThrownBy { repository.findByUserId(owner, pageWithSort) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
