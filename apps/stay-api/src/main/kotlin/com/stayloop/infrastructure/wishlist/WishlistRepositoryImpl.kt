package com.stayloop.infrastructure.wishlist

import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.wishlist.WishlistId
import com.stayloop.domain.wishlist.WishlistModel
import com.stayloop.domain.wishlist.WishlistRepository
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 찜 Repository 구현. `LoginId → users.id` 변환을 [UserRepository] 로 캡슐화한다.
 * 사용자 부재 정책은 메서드별로 다르다 — 조회/존재/삭제는 관대(빈/거짓/noop), 저장만 NOT_FOUND.
 */
@Component
class WishlistRepositoryImpl(
    private val userRepository: UserRepository,
    private val wishlistJpaRepository: WishlistJpaRepository,
) : WishlistRepository {
    override fun existsBy(loginId: LoginId, propertyId: Long): Boolean {
        val userId = resolveUserIdOrNull(loginId) ?: return false
        return wishlistJpaRepository.existsById(WishlistId(userId, propertyId))
    }

    override fun save(loginId: LoginId, propertyId: Long, wishedAt: LocalDateTime): WishlistModel {
        val userId = resolveUserIdOrNull(loginId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 사용자입니다.")
        return wishlistJpaRepository.save(WishlistModel(userId, propertyId, wishedAt))
    }

    override fun deleteBy(loginId: LoginId, propertyId: Long) {
        val userId = resolveUserIdOrNull(loginId) ?: return
        val id = WishlistId(userId, propertyId)
        if (wishlistJpaRepository.existsById(id)) {
            wishlistJpaRepository.deleteById(id)
        }
    }

    override fun findByUserId(loginId: LoginId, page: Int, size: Int): List<WishlistModel> {
        val userId = resolveUserIdOrNull(loginId) ?: return emptyList()
        return wishlistJpaRepository.findByUserId(userId, PageRequest.of(page, size))
    }

    private fun resolveUserIdOrNull(loginId: LoginId): Long? = userRepository.findByLoginId(loginId)?.id
}
