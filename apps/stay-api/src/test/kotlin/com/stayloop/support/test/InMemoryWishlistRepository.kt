package com.stayloop.support.test

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.wishlist.WishlistId
import com.stayloop.domain.wishlist.WishlistModel
import com.stayloop.domain.wishlist.WishlistRepository
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import java.time.LocalDateTime

/**
 * 테스트용 InMemory `WishlistRepository`. 운영 RepositoryImpl 의 의미론과 동치 —
 * - **boundary 는 LoginId**, 내부 저장은 `(users.id, propertyId)` 자연키
 * - 사용자 부재 시 정책 (`existsBy` / `findByUserId` silent empty / `save` / `deleteBy`)
 * - `findByUserId` 는 `wishedAt DESC` 정렬, `PageQuery` 의 offset/limit 적용
 *
 * 운영-테스트 동치성을 위해 `UserRepository` 를 주입받아 `LoginId ↔ users.id` 변환을 동일하게 수행한다
 * (verify-code §19-A — 더블이 운영보다 단순화하면 회귀 사각지대).
 */
class InMemoryWishlistRepository(
    private val users: UserRepository,
) : WishlistRepository {
    private val store = mutableMapOf<WishlistId, WishlistModel>()

    override fun existsBy(userId: LoginId, propertyId: Long): Boolean {
        val resolved = users.findByLoginId(userId)?.id ?: return false
        return store.containsKey(WishlistId(resolved, propertyId))
    }

    override fun save(userId: LoginId, propertyId: Long, wishedAt: LocalDateTime): WishlistModel {
        val resolved = users.findByLoginId(userId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "사용자가 존재하지 않습니다: ${userId.value}")
        val model = WishlistModel.create(resolved.id, propertyId, wishedAt)
        store[WishlistId(resolved.id, propertyId)] = model
        return model
    }

    override fun deleteBy(userId: LoginId, propertyId: Long) {
        val resolved = users.findByLoginId(userId)?.id ?: return
        store.remove(WishlistId(resolved, propertyId))
    }

    override fun findByUserId(userId: LoginId, page: PageQuery): List<WishlistModel> {
        val resolved = users.findByLoginId(userId)?.id ?: return emptyList()
        return store.values
            .filter { it.userId == resolved }
            .sortedByDescending { it.wishedAt }
            .drop(page.offset)
            .take(page.limit)
    }
}
