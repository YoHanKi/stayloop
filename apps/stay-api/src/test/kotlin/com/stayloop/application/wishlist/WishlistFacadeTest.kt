package com.stayloop.application.wishlist

import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.value.Address
import com.stayloop.domain.property.value.PropertyCategory
import com.stayloop.domain.property.value.PropertyName
import com.stayloop.domain.property.value.PropertyPolicy
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import com.stayloop.support.test.InMemoryPropertyRepository
import com.stayloop.support.test.InMemoryUserRepository
import com.stayloop.support.test.InMemoryWishlistRepository
import com.stayloop.support.test.UserFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WishlistFacadeTest {
    private lateinit var propertyRepository: InMemoryPropertyRepository
    private lateinit var wishlistRepository: InMemoryWishlistRepository
    private lateinit var sut: WishlistFacade

    private val alice = LoginId("alice01")
    private val bob = LoginId("bobby02")
    private var propertyId: Long = 0

    @BeforeEach
    fun setUp() {
        val userRepository = InMemoryUserRepository()
        UserFixture.save(userRepository, "alice01")
        UserFixture.save(userRepository, "bobby02")
        propertyRepository = InMemoryPropertyRepository()
        wishlistRepository = InMemoryWishlistRepository(userRepository)
        val clock = Clock.fixed(Instant.parse("2026-06-06T00:00:00Z"), ZoneOffset.UTC)
        sut = WishlistFacade(wishlistRepository, propertyRepository, clock)

        propertyId = propertyRepository.save(
            PropertyModel.create(
                name = PropertyName("스테이루프 호텔"),
                category = PropertyCategory.HOTEL,
                address = Address("seoul", "서울특별시 중구 세종대로 110"),
                policy = PropertyPolicy.standard(),
            ),
        ).id
    }

    @DisplayName("찜을 두 번 눌러도 행은 하나, wishCount 는 1 만 증가한다(AC-6).")
    @Test
    fun shouldBeIdempotentOnRepeatedWish() {
        sut.wish(alice, propertyId)
        val second = sut.wish(alice, propertyId)

        assertThat(second.wished).isTrue()
        assertThat(second.wishCount).isEqualTo(1)
        assertThat(propertyRepository.findById(propertyId)!!.wishCount).isEqualTo(1)
    }

    @DisplayName("찜 취소를 두 번 눌러도 wishCount 는 0 밑으로 내려가지 않는다(AC-6).")
    @Test
    fun shouldBeIdempotentOnRepeatedUnwish() {
        sut.wish(alice, propertyId)

        sut.unwish(alice, propertyId)
        val second = sut.unwish(alice, propertyId)

        assertThat(second.wished).isFalse()
        assertThat(second.wishCount).isEqualTo(0)
    }

    @DisplayName("내 찜 목록은 본인만 조회할 수 있고 타인 조회는 403 으로 거절된다(AC-7).")
    @Test
    fun shouldRejectAnotherUserAccess() {
        sut.wish(alice, propertyId)

        assertThat(sut.getMyWishes(requester = alice, target = alice, page = 0, size = 20)).hasSize(1)
        assertThatThrownBy { sut.getMyWishes(requester = bob, target = alice, page = 0, size = 20) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.FORBIDDEN)
    }

    @DisplayName("존재하지 않는 숙소를 찜하면 NOT_FOUND 로 거절된다.")
    @Test
    fun shouldReject_whenPropertyNotFound() {
        assertThatThrownBy { sut.wish(alice, 999L) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND)
    }
}
