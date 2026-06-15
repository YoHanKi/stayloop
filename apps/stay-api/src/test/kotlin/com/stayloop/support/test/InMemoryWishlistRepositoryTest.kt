package com.stayloop.support.test

import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class InMemoryWishlistRepositoryTest {
    private lateinit var userRepository: InMemoryUserRepository
    private lateinit var repository: InMemoryWishlistRepository

    private val alice = LoginId("alice01")
    private val bob = LoginId("bobby02")
    private val ghost = LoginId("ghost99")

    @BeforeEach
    fun setUp() {
        userRepository = InMemoryUserRepository()
        UserFixture.save(userRepository, "alice01")
        UserFixture.save(userRepository, "bobby02")
        repository = InMemoryWishlistRepository(userRepository)
    }

    @DisplayName("add 는 새로 추가하면 true, 이미 있으면 false 이고 행은 하나로 유지된다.")
    @Test
    fun shouldAddReturningWhetherNew() {
        assertThat(repository.add(alice, 1L, LocalDateTime.now())).isTrue()
        assertThat(repository.add(alice, 1L, LocalDateTime.now())).isFalse()
        assertThat(repository.findByUserId(alice, 0, 10)).hasSize(1)
    }

    @DisplayName("remove 는 실제로 제거하면 true, 없으면 false 이고 멱등하다.")
    @Test
    fun shouldRemoveReturningWhetherDeleted() {
        repository.add(alice, 1L, LocalDateTime.now())

        assertThat(repository.remove(alice, 1L)).isTrue()
        assertThat(repository.remove(alice, 1L)).isFalse()
        assertThat(repository.findByUserId(alice, 0, 10)).isEmpty()
    }

    @DisplayName("찜 목록은 사용자별로 격리되고 wishedAt 내림차순으로 정렬된다.")
    @Test
    fun shouldIsolateAndSortDesc() {
        val older = LocalDateTime.of(2026, 6, 1, 10, 0)
        val newer = LocalDateTime.of(2026, 6, 2, 10, 0)
        repository.add(alice, 1L, older)
        repository.add(alice, 2L, newer)
        repository.add(bob, 3L, newer)

        val aliceWishes = repository.findByUserId(alice, 0, 10)

        assertThat(aliceWishes.map { it.propertyId }).containsExactly(2L, 1L)
    }

    @DisplayName("페이지네이션은 page·size 로 잘라낸다.")
    @Test
    fun shouldPaginate() {
        (1L..5L).forEach { repository.add(alice, it, LocalDateTime.of(2026, 6, it.toInt(), 10, 0)) }

        assertThat(repository.findByUserId(alice, 0, 2)).hasSize(2)
        assertThat(repository.findByUserId(alice, 2, 2)).hasSize(1)
    }

    @DisplayName("사용자가 없으면 조회는 빈 목록, 제거는 거짓, 추가만 NOT_FOUND.")
    @Test
    fun shouldApplyAbsentUserPolicies() {
        assertThat(repository.findByUserId(ghost, 0, 10)).isEmpty()
        assertThat(repository.remove(ghost, 1L)).isFalse()
        assertThatThrownBy { repository.add(ghost, 1L, LocalDateTime.now()) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND)
    }
}
