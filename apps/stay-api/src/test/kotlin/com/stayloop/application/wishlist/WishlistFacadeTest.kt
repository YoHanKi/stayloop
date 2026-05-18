package com.stayloop.application.wishlist

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.common.value.SortDirection
import com.stayloop.domain.common.value.SortKey
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.value.Address
import com.stayloop.domain.property.value.Amenities
import com.stayloop.domain.property.value.CancellationPolicy
import com.stayloop.domain.property.value.CancellationType
import com.stayloop.domain.property.value.Name
import com.stayloop.domain.property.value.PropertyCategory
import com.stayloop.domain.property.value.PropertyPolicy
import com.stayloop.domain.user.UserModel
import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import com.stayloop.support.test.FakePasswordEncoder
import com.stayloop.support.test.InMemoryPropertyRepository
import com.stayloop.support.test.InMemoryUserRepository
import com.stayloop.support.test.InMemoryWishlistRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import com.stayloop.domain.user.value.Name as UserName

class WishlistFacadeTest {
    private lateinit var users: InMemoryUserRepository
    private lateinit var properties: InMemoryPropertyRepository
    private lateinit var wishes: InMemoryWishlistRepository
    private lateinit var cacheStore: com.stayloop.support.test.InMemoryCacheStore
    private lateinit var sut: WishlistFacade
    private val fixedClock: Clock = Clock.fixed(
        java.time.Instant.parse("2026-05-04T10:15:30Z"),
        ZoneId.of("Asia/Seoul"),
    )

    @BeforeEach
    fun setUp() {
        users = InMemoryUserRepository()
        properties = InMemoryPropertyRepository()
        wishes = InMemoryWishlistRepository(users)
        cacheStore = com.stayloop.support.test.InMemoryCacheStore()
        sut = WishlistFacade(
            wishlistRepository = wishes,
            propertyRepository = properties,
            cacheStore = cacheStore,
            clock = fixedClock,
        )
    }

    @DisplayName("wish 는 처음 호출 시 wishCount 를 1 증가시킨다.")
    @Test
    fun shouldIncrementWishCountOnFirstWish() {
        val user = saveUser("alen01")
        val property = saveProperty()

        val info = sut.wish(user.loginId, property.id)

        assertThat(info.wished).isTrue()
        assertThat(info.wishCount).isEqualTo(1)
        assertThat(properties.findById(property.id)?.wishCount).isEqualTo(1)
    }

    @DisplayName("wish 멱등 — 이미 찜된 경우 wishCount 가 그대로 유지된다 (AC-6).")
    @Test
    fun shouldBeIdempotentOnRepeatedWish() {
        val user = saveUser("alen01")
        val property = saveProperty()

        sut.wish(user.loginId, property.id)
        val second = sut.wish(user.loginId, property.id)

        assertThat(second.wished).isTrue()
        assertThat(second.wishCount).isEqualTo(1)
        assertThat(properties.findById(property.id)?.wishCount).isEqualTo(1)
    }

    @DisplayName("unwish 는 찜된 행을 제거하고 wishCount 를 1 감소시킨다.")
    @Test
    fun shouldDecrementWishCountOnUnwish() {
        val user = saveUser("alen01")
        val property = saveProperty()
        sut.wish(user.loginId, property.id)

        val info = sut.unwish(user.loginId, property.id)

        assertThat(info.wished).isFalse()
        assertThat(info.wishCount).isEqualTo(0)
        assertThat(wishes.existsBy(user.loginId, property.id)).isFalse()
    }

    @DisplayName("unwish 멱등 — 찜되지 않은 경우 wishCount 가 그대로 유지된다 (AC-6).")
    @Test
    fun shouldBeIdempotentOnRepeatedUnwish() {
        val user = saveUser("alen01")
        val property = saveProperty()

        // 처음부터 찜이 없음
        val info = sut.unwish(user.loginId, property.id)

        assertThat(info.wished).isFalse()
        assertThat(info.wishCount).isEqualTo(0)
        assertThat(properties.findById(property.id)?.wishCount).isEqualTo(0)
    }

    @DisplayName("wish 는 detail cache 의 property:detail:{id} 를 evict 하여 다음 read 가 stale 응답을 피한다 (PR4 A-3).")
    @Test
    fun shouldEvictDetailCacheOnWish() {
        val user = saveUser("alen01")
        val property = saveProperty()
        val cacheKey = "property:detail:${property.id}"
        // 사전 박제 — getDetail 응답을 흉내낸 임의 payload (key 존재만 검증 목적)
        cacheStore.put(cacheKey, "stale-detail-payload", java.time.Duration.ofMinutes(10))
        assertThat(cacheStore.get(cacheKey, String::class.java)).isNotNull

        sut.wish(user.loginId, property.id)

        // TX 가 없는 단위 테스트 — Facade 의 분기 (`isSynchronizationActive() == false`) 에 따라 즉시 evict 수행.
        assertThat(cacheStore.get(cacheKey, String::class.java)).isNull()
    }

    @DisplayName("unwish 는 실제 감소가 발생한 경우에만 detail cache 를 evict 한다 (이미 0 이면 noop, PR4 A-3).")
    @Test
    fun shouldEvictDetailCacheOnUnwishOnlyWhenAffected() {
        val user = saveUser("alen01")
        val property = saveProperty()
        // 먼저 wish 로 카운트 1 (이 호출이 evict 도 실행) — 그 후 cache 재박제
        sut.wish(user.loginId, property.id)
        val cacheKey = "property:detail:${property.id}"
        cacheStore.put(cacheKey, "stale-after-wish", java.time.Duration.ofMinutes(10))

        // (1) 실제 감소 발생 (affected=1) — cache evict
        sut.unwish(user.loginId, property.id)
        assertThat(cacheStore.get(cacheKey, String::class.java)).isNull()

        // (2) 멱등 unwish (이미 0) — cache 가 박제되어 있어도 evict 호출 안 됨
        cacheStore.put(cacheKey, "should-survive-noop-unwish", java.time.Duration.ofMinutes(10))
        sut.unwish(user.loginId, property.id)
        assertThat(cacheStore.get(cacheKey, String::class.java)).isEqualTo("should-survive-noop-unwish")
    }

    @DisplayName("wish 는 존재하지 않는 propertyId 에 대해 NOT_FOUND 를 던진다.")
    @Test
    fun shouldThrowNotFoundOnUnknownProperty() {
        val user = saveUser("alen01")

        assertThatThrownBy { sut.wish(user.loginId, 999L) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND)
    }

    @DisplayName("getMyWishes 는 본인 자원만 조회 가능 — 다른 LoginId 면 FORBIDDEN (AC-7).")
    @Test
    fun shouldRejectAnotherUserAccess() {
        val alen = saveUser("alen01")
        val others = saveUser("other02")
        val property = saveProperty()
        sut.wish(alen.loginId, property.id)

        assertThatThrownBy {
            sut.getMyWishes(loginId = others.loginId, targetUserId = alen.loginId, page = PageQuery(0, 20))
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.FORBIDDEN)
    }

    @DisplayName("getMyWishes 는 본인 찜 목록을 wishedAt DESC 순으로 반환한다.")
    @Test
    fun shouldReturnMyWishesSortedByWishedAtDesc() {
        val user = saveUser("alen01")
        val older = saveProperty(name = "강남호텔")
        val newer = saveProperty(name = "해운대리조트")
        // sut.wish 는 fixedClock 으로 같은 시각이 되어 정렬 검증이 무의미해진다.
        // Repository 에 직접 *서로 다른 wishedAt* 으로 seed 해 DESC 순서를 회귀 가드로 박는다
        // (verify-tests §4-A — DisplayName 의 "DESC 순" 약속과 어설션 정합).
        wishes.save(user.loginId, older.id, LocalDateTime.parse("2026-05-04T10:00:00"))
        wishes.save(user.loginId, newer.id, LocalDateTime.parse("2026-05-04T11:00:00"))

        val items = sut.getMyWishes(user.loginId, user.loginId, PageQuery(0, 20))

        assertThat(items.map { it.propertyId }).containsExactly(newer.id, older.id)
    }

    @DisplayName("getMyWishes 는 page.sort 가 비어있지 않으면 BAD_REQUEST 로 거절한다.")
    @Test
    fun shouldRejectNonEmptySortAsBadRequest() {
        val user = saveUser("alen01")

        assertThatThrownBy {
            sut.getMyWishes(
                loginId = user.loginId,
                targetUserId = user.loginId,
                page = PageQuery(0, 20, listOf(SortKey("wishedAt", SortDirection.ASC))),
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("getMyWishes 는 wishlist 행이 가리키는 Property 가 누락되면 INTERNAL_ERROR 로 명시 실패한다.")
    @Test
    fun shouldFailExplicitlyWhenReferencedPropertyMissing() {
        val user = saveUser("alen01")
        val existing = saveProperty(name = "강남호텔")
        // 존재하지 않는 propertyId 로 직접 wishlist 행 seed — 데이터 정합 깨짐 시뮬레이션.
        wishes.save(user.loginId, existing.id, LocalDateTime.parse("2026-05-04T10:00:00"))
        wishes.save(user.loginId, 9_999L, LocalDateTime.parse("2026-05-04T11:00:00"))

        assertThatThrownBy {
            sut.getMyWishes(user.loginId, user.loginId, PageQuery(0, 20))
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

    private fun saveProperty(name: String = "테스트호텔"): PropertyModel {
        val property = PropertyModel.create(
            name = Name(name),
            category = PropertyCategory.HOTEL,
            description = "테스트용 숙소",
            address = Address(city = "SEOUL", fullAddress = "SEOUL 어딘가 123"),
            amenities = Amenities.EMPTY,
            policy = PropertyPolicy(
                checkInTime = LocalTime.of(15, 0),
                checkOutTime = LocalTime.of(11, 0),
                cancellation = CancellationPolicy(type = CancellationType.FREE_UNTIL, freeUntilDaysBefore = 3),
            ),
        )
        return properties.save(property)
    }
}
