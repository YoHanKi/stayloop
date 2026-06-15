package com.stayloop.infrastructure.wishlist

import com.querydsl.jpa.impl.JPAQueryFactory
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.wishlist.QWishlistModel
import com.stayloop.domain.wishlist.WishlistModel
import com.stayloop.domain.wishlist.WishlistRepository
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 찜 Repository 구현. `LoginId → users.id` 변환을 [UserRepository] 로 캡슐화한다.
 * 사용자 부재 정책은 메서드별로 다르다 — 조회/제거는 관대(빈/거짓), 추가만 NOT_FOUND.
 */
@Component
class WishlistRepositoryImpl(
    private val userRepository: UserRepository,
    private val wishlistJpaRepository: WishlistJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : WishlistRepository {
    private val wishlist = QWishlistModel.wishlistModel

    override fun add(loginId: LoginId, propertyId: Long, wishedAt: LocalDateTime): Boolean {
        val userId = resolveUserIdOrNull(loginId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 사용자입니다.")
        return wishlistJpaRepository.insertIgnore(userId, propertyId, wishedAt) == 1
    }

    override fun remove(loginId: LoginId, propertyId: Long): Boolean {
        val userId = resolveUserIdOrNull(loginId) ?: return false
        return queryFactory
            .delete(wishlist)
            .where(wishlist.userId.eq(userId), wishlist.propertyId.eq(propertyId))
            .execute() > 0
    }

    override fun findByUserId(loginId: LoginId, page: Int, size: Int): List<WishlistModel> {
        val userId = resolveUserIdOrNull(loginId) ?: return emptyList()
        return queryFactory
            .selectFrom(wishlist)
            .where(wishlist.userId.eq(userId))
            .orderBy(wishlist.wishedAt.desc())
            .offset(page.toLong() * size)
            .limit(size.toLong())
            .fetch()
    }

    private fun resolveUserIdOrNull(loginId: LoginId): Long? = userRepository.findByLoginId(loginId)?.id
}
