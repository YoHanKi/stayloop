package com.stayloop.support.test

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.common.value.SortDirection
import com.stayloop.domain.common.value.SortKey
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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class InMemoryWishlistRepositoryTest {
    private val users = InMemoryUserRepository()
    private val repository = InMemoryWishlistRepository(users)
    private val encoder = FakePasswordEncoder()
    private val loginA = LoginId("alpha01")
    private val loginB = LoginId("bravo02")
    private val unknown = LoginId("ghost99")

    init {
        users.save(userOf(loginA))
        users.save(userOf(loginB))
    }

    @DisplayName("save 는 새 찜 행을 저장하고 existsBy 는 이후 true 를 반환한다.")
    @Test
    fun shouldSaveAndExist() {
        repository.save(loginA, propertyId = 1L, wishedAt = at(10, 0))

        assertThat(repository.existsBy(loginA, 1L)).isTrue()
    }

    @DisplayName("같은 자연키 (userId, propertyId) 에 save 가 두 번 호출되면 두 번째 wishedAt 으로 upsert 된다 — 행 수 1.")
    @Test
    fun shouldUpsertOnSameNaturalKey() {
        repository.save(loginA, propertyId = 1L, wishedAt = at(10, 0))
        repository.save(loginA, propertyId = 1L, wishedAt = at(11, 30))

        val list = repository.findByUserId(loginA, PageQuery.of(page = 0, size = 10))
        assertThat(list).hasSize(1)
        assertThat(list[0].wishedAt).isEqualTo(at(11, 30))
    }

    @DisplayName("deleteBy 는 자연키 행을 제거하고, 행이 없으면 noop — existsBy 가 false 가 된다.")
    @Test
    fun shouldDeleteAndBecomeNonExistent() {
        repository.save(loginA, propertyId = 1L, wishedAt = at(10, 0))
        repository.deleteBy(loginA, 1L)
        repository.deleteBy(loginA, 999L) // 없는 행 — 예외 없이 통과해야 한다

        assertThat(repository.existsBy(loginA, 1L)).isFalse()
    }

    @DisplayName("findByUserId 는 다른 사용자의 찜을 섞지 않으며 wishedAt 내림차순(최신순) 으로 정렬된다.")
    @Test
    fun shouldFilterByUserAndSortDesc() {
        // 입력은 일부러 시간순/뒤섞인 순서 — sortedByDescending 가 빠지면 containsExactly 가 실패한다.
        repository.save(loginA, propertyId = 1L, wishedAt = at(10, 0))
        repository.save(loginA, propertyId = 3L, wishedAt = at(12, 0))
        repository.save(loginA, propertyId = 2L, wishedAt = at(11, 0))
        repository.save(loginB, propertyId = 99L, wishedAt = at(13, 0)) // 다른 사용자 — 결과에 섞이면 안 됨

        val list = repository.findByUserId(loginA, PageQuery.of(page = 0, size = 10))

        assertThat(list).extracting("propertyId")
            .containsExactly(3L, 2L, 1L) // 12:00 > 11:00 > 10:00 순
    }

    @DisplayName("findByUserId 는 PageQuery 의 offset / limit 을 적용한다 — page=1 size=2 일 때 3개 중 마지막 1개.")
    @Test
    fun shouldApplyPagination() {
        repository.save(loginA, propertyId = 1L, wishedAt = at(10, 0))
        repository.save(loginA, propertyId = 2L, wishedAt = at(11, 0))
        repository.save(loginA, propertyId = 3L, wishedAt = at(12, 0))

        // sort: 12:00, 11:00, 10:00 → page=1 size=2 → offset=2, limit=2 → 1개 (10:00 의 propertyId=1)
        val list = repository.findByUserId(loginA, PageQuery.of(page = 1, size = 2))

        assertThat(list).extracting("propertyId").containsExactly(1L)
    }

    @DisplayName("사용자가 없는 LoginId 로 existsBy 호출 시 false — 저장된 행이 없는 것과 같다.")
    @Test
    fun shouldReturnFalse_whenUserMissing_existsBy() {
        assertThat(repository.existsBy(unknown, 1L)).isFalse()
    }

    @DisplayName("사용자가 없는 LoginId 로 findByUserId 호출 시 빈 리스트 — silent empty.")
    @Test
    fun shouldReturnEmpty_whenUserMissing_findByUserId() {
        assertThat(repository.findByUserId(unknown, PageQuery.of(page = 0, size = 10))).isEmpty()
    }

    @DisplayName("사용자가 없는 LoginId 로 save 호출 시 NOT_FOUND — 쓰기는 사용자 부재가 명시적 오류, 메시지에 LoginId 가 포함되지 않는다.")
    @Test
    fun shouldThrowNotFound_whenUserMissing_save_withoutExposingLoginId() {
        // 단일 assertThatThrownBy 에서 (1) errorType (2) 메시지 일반화 (3) LoginId 미노출 모두 검증 —
        // 두 블록으로 나누면 어설션 강도 비대칭이 회귀 사각지대 (verify-code §19-B).
        assertThatThrownBy { repository.save(unknown, 1L, at(10, 0)) }
            .isInstanceOf(CoreException::class.java)
            .hasMessage("사용자가 존재하지 않습니다.")
            .hasMessageNotContaining(unknown.value) // `customMessage` 가 응답으로 흘러가므로 식별자 미노출 (§12 / §18)
            .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND)
    }

    @DisplayName("findByUserId 는 page.sort 가 비어있지 않으면 BAD_REQUEST 로 거절한다 — silent ignore 차단(§16-A).")
    @Test
    fun shouldRejectBadRequest_whenPageQuerySortIsNotEmpty() {
        val pageWithSort = PageQuery.of(
            page = 0,
            size = 10,
            sort = listOf(SortKey("wishedAt", SortDirection.ASC)),
        )

        assertThatThrownBy { repository.findByUserId(loginA, pageWithSort) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("사용자가 없는 LoginId 로 deleteBy 호출 시 noop — 삭제할 행 자체가 없으므로 예외 없음.")
    @Test
    fun shouldNoop_whenUserMissing_deleteBy() {
        repository.deleteBy(unknown, 1L)
        // 도달만 하면 OK (예외 없음). 다른 사용자의 행에 영향 없음을 확인:
        repository.save(loginA, 1L, at(10, 0))
        repository.deleteBy(unknown, 1L)
        assertThat(repository.existsBy(loginA, 1L)).isTrue()
    }

    private fun at(hour: Int, minute: Int): LocalDateTime =
        LocalDateTime.of(2026, 5, 10, hour, minute)

    private fun userOf(loginId: LoginId): UserModel = UserModel.create(
        loginId = loginId,
        rawPassword = "Abcd1234!",
        name = Name("홍길동"),
        birthDate = BirthDate(LocalDate.of(2000, 1, 1)),
        email = Email("alen@stayloop.io"),
        phoneNumber = PhoneNumber("010-1234-5678"),
        encoder = encoder,
    )
}
