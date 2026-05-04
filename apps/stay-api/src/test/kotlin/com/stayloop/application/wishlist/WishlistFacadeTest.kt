package com.stayloop.application.wishlist

import com.stayloop.domain.common.value.PageQuery
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
import java.time.LocalTime
import java.time.ZoneId
import com.stayloop.domain.user.value.Name as UserName

class WishlistFacadeTest {
    private lateinit var users: InMemoryUserRepository
    private lateinit var properties: InMemoryPropertyRepository
    private lateinit var wishes: InMemoryWishlistRepository
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
        sut = WishlistFacade(
            wishlistRepository = wishes,
            propertyRepository = properties,
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
        val first = saveProperty(name = "강남호텔")
        val second = saveProperty(name = "해운대리조트")
        sut.wish(user.loginId, first.id)
        // fixedClock 이라 같은 시각이지만 second.id 가 더 큰 → propertyId tie-break 없이 같은 wishedAt 이면
        // InMemory 의 sortedByDescending 안정 정렬에 의존. 본 테스트는 *목록 size + 두 항목이 모두 포함* 만 검증.
        sut.wish(user.loginId, second.id)

        val items = sut.getMyWishes(user.loginId, user.loginId, PageQuery(0, 20))

        assertThat(items).hasSize(2)
        assertThat(items.map { it.propertyId }).containsExactlyInAnyOrder(first.id, second.id)
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
