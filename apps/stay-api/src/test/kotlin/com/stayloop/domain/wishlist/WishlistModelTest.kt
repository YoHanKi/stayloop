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
    @DisplayName("유효한 사용자·숙소 ID 로 찜이 생성된다.")
    @Test
    fun shouldCreate_whenIdsValid() {
        val wishlist = WishlistModel(userId = 1L, propertyId = 2L, wishedAt = LocalDateTime.now())

        assertThat(wishlist.userId).isEqualTo(1L)
        assertThat(wishlist.propertyId).isEqualTo(2L)
    }

    @DisplayName("사용자 ID 가 0 이하면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun shouldReject_whenUserIdNotPositive(userId: Long) {
        assertThatThrownBy { WishlistModel(userId = userId, propertyId = 1L, wishedAt = LocalDateTime.now()) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("숙소 ID 가 0 이하면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun shouldReject_whenPropertyIdNotPositive(propertyId: Long) {
        assertThatThrownBy { WishlistModel(userId = 1L, propertyId = propertyId, wishedAt = LocalDateTime.now()) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
