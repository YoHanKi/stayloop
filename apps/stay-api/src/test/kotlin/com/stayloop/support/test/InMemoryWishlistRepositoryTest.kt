package com.stayloop.support.test

import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
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

    @DisplayName("찜을 저장하면 existsBy 가 참이 된다.")
    @Test
    fun shouldSaveAndExist() {
        repository.save(alice, propertyId = 1L, wishedAt = LocalDateTime.now())

        assertThat(repository.existsBy(alice, 1L)).isTrue()
    }

    @DisplayName("같은 사용자·숙소를 다시 저장해도 행은 하나다(upsert).")
    @Test
    fun shouldUpsert() {
        repository.save(alice, 1L, LocalDateTime.now())
        repository.save(alice, 1L, LocalDateTime.now())

        assertThat(repository.findByUserId(alice, 0, 10)).hasSize(1)
    }

    @DisplayName("찜 삭제는 멱등하다 — 없는 찜을 삭제해도 예외가 없다.")
    @Test
    fun shouldDeleteIdempotently() {
        repository.save(alice, 1L, LocalDateTime.now())
        repository.deleteBy(alice, 1L)

        assertThat(repository.existsBy(alice, 1L)).isFalse()
        assertThatCode { repository.deleteBy(alice, 1L) }.doesNotThrowAnyException()
    }

    @DisplayName("찜 목록은 사용자별로 격리되고 wishedAt 내림차순으로 정렬된다.")
    @Test
    fun shouldIsolateAndSortDesc() {
        val older = LocalDateTime.of(2026, 6, 1, 10, 0)
        val newer = LocalDateTime.of(2026, 6, 2, 10, 0)
        repository.save(alice, 1L, older)
        repository.save(alice, 2L, newer)
        repository.save(bob, 3L, newer)

        val aliceWishes = repository.findByUserId(alice, 0, 10)

        assertThat(aliceWishes.map { it.propertyId }).containsExactly(2L, 1L)
    }

    @DisplayName("페이지네이션은 page·size 로 잘라낸다.")
    @Test
    fun shouldPaginate() {
        (1L..5L).forEach { repository.save(alice, it, LocalDateTime.of(2026, 6, it.toInt(), 10, 0)) }

        assertThat(repository.findByUserId(alice, 0, 2)).hasSize(2)
        assertThat(repository.findByUserId(alice, 2, 2)).hasSize(1)
    }

    @DisplayName("사용자가 없으면 존재는 거짓, 조회는 빈 목록, 삭제는 noop, 저장만 NOT_FOUND.")
    @Test
    fun shouldApplyAbsentUserPolicies() {
        assertThat(repository.existsBy(ghost, 1L)).isFalse()
        assertThat(repository.findByUserId(ghost, 0, 10)).isEmpty()
        assertThatCode { repository.deleteBy(ghost, 1L) }.doesNotThrowAnyException()
        assertThatThrownBy { repository.save(ghost, 1L, LocalDateTime.now()) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND)
    }
}
