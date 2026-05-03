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
 * 테스트용 InMemory `WishlistRepository`. 운영 RepositoryImpl 의 의미론과 **동일 정책** —
 * - **boundary 는 LoginId**, 내부 저장은 `(users.id, propertyId)` 자연키
 * - 사용자 부재: read silent empty / save NOT_FOUND (메시지 일반화, LoginId 미포함) / deleteBy noop
 * - `findByUserId`: `wishedAt DESC` 고정, `page.sort` 비어있지 않으면 BAD_REQUEST 거절
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
            ?: throw CoreException(ErrorType.NOT_FOUND, USER_NOT_FOUND_MESSAGE)
        val model = WishlistModel.create(resolved.id, propertyId, wishedAt)
        store[WishlistId(resolved.id, propertyId)] = model
        return model
    }

    override fun deleteBy(userId: LoginId, propertyId: Long) {
        val resolved = users.findByLoginId(userId)?.id ?: return
        store.remove(WishlistId(resolved, propertyId))
    }

    override fun findByUserId(userId: LoginId, page: PageQuery): List<WishlistModel> {
        if (page.sort.isNotEmpty()) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "Wishlist 목록은 최근 찜 순으로 고정 정렬되며, 사용자 정의 정렬을 지원하지 않습니다.",
            )
        }
        val resolved = users.findByLoginId(userId)?.id ?: return emptyList()
        return store.values
            .filter { it.userId == resolved }
            .sortedByDescending { it.wishedAt }
            .drop(page.offset)
            .take(page.limit)
    }

    companion object {
        // 운영(WishlistRepositoryImpl) 의 메시지와 *문자열까지 동일* — 테스트가 운영의 응답 메시지 정책을
        // 그대로 검증할 수 있게 한다 (verify-code §19-A).
        private const val USER_NOT_FOUND_MESSAGE = "사용자가 존재하지 않습니다."
    }
}
