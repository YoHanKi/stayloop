package com.stayloop.domain.wishlist

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDateTime

class WishlistModelTest {
    private val anyTime = LocalDateTime.of(2026, 5, 10, 12, 0)

    @DisplayName("정상 생성 시 userId / propertyId / wishedAt 가 그대로 노출된다.")
    @Test
    fun shouldExposeFields() {
        val wish = WishlistModel.create(userId = 7L, propertyId = 42L, wishedAt = anyTime)

        assertThat(wish.userId).isEqualTo(7L)
        assertThat(wish.propertyId).isEqualTo(42L)
        assertThat(wish.wishedAt).isEqualTo(anyTime)
    }

    @DisplayName("userId 가 0 이하(0 / 음수) 이면 BAD_REQUEST 로 거절된다 — 영속화된 User 참조 필수.")
    @ParameterizedTest
    @ValueSource(longs = [0L, -1L, -100L])
    fun shouldReject_whenUserIdIsZeroOrNegative(userId: Long) {
        assertThatThrownBy {
            WishlistModel.create(userId = userId, propertyId = 42L, wishedAt = anyTime)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("propertyId 가 0 이하(0 / 음수) 이면 BAD_REQUEST 로 거절된다 — 영속화된 Property 참조 필수.")
    @ParameterizedTest
    @ValueSource(longs = [0L, -1L, -100L])
    fun shouldReject_whenPropertyIdIsZeroOrNegative(propertyId: Long) {
        assertThatThrownBy {
            WishlistModel.create(userId = 7L, propertyId = propertyId, wishedAt = anyTime)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
