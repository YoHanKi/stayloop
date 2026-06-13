package com.stayloop.support.test

import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.wishlist.WishlistId
import com.stayloop.domain.wishlist.WishlistModel
import com.stayloop.domain.wishlist.WishlistRepository
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import java.time.LocalDateTime

/**
 * 운영 [com.stayloop.infrastructure.wishlist.WishlistRepositoryImpl] 과 동치 의미론의 더블 —
 * LoginId 경계, 사용자 부재 정책(조회 빈/존재 거짓/삭제 noop/저장 NOT_FOUND), wishedAt DESC, upsert.
 */
class InMemoryWishlistRepository(
    private val userRepository: UserRepository,
) : WishlistRepository {
    private val store = LinkedHashMap<WishlistId, WishlistModel>()

    override fun existsBy(loginId: LoginId, propertyId: Long): Boolean {
        val userId = resolveUserIdOrNull(loginId) ?: return false
        return store.containsKey(WishlistId(userId, propertyId))
    }

    override fun save(loginId: LoginId, propertyId: Long, wishedAt: LocalDateTime): WishlistModel {
        val userId = resolveUserIdOrNull(loginId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 사용자입니다.")
        val model = WishlistModel(userId, propertyId, wishedAt)
        store[WishlistId(userId, propertyId)] = model
        return model
    }

    override fun deleteBy(loginId: LoginId, propertyId: Long) {
        val userId = resolveUserIdOrNull(loginId) ?: return
        store.remove(WishlistId(userId, propertyId))
    }

    override fun findByUserId(loginId: LoginId, page: Int, size: Int): List<WishlistModel> {
        val userId = resolveUserIdOrNull(loginId) ?: return emptyList()
        return store.values
            .filter { it.userId == userId }
            .sortedByDescending { it.wishedAt }
            .drop(page * size)
            .take(size)
    }

    private fun resolveUserIdOrNull(loginId: LoginId): Long? = userRepository.findByLoginId(loginId)?.id
}
