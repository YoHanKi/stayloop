package com.stayloop.domain.wishlist

import com.stayloop.domain.user.value.LoginId
import java.time.LocalDateTime

/**
 * 찜 Repository. 경계는 `users.id` 가 아니라 [LoginId] 로 노출하고, `LoginId ↔ users.id` 변환은
 * 구현(`WishlistRepositoryImpl`)이 캡슐화한다(week2-3 §⑥).
 */
interface WishlistRepository {
    fun existsBy(loginId: LoginId, propertyId: Long): Boolean

    fun save(loginId: LoginId, propertyId: Long, wishedAt: LocalDateTime): WishlistModel

    /** 멱등 삭제 — 찜이 없거나 사용자가 없으면 no-op. */
    fun deleteBy(loginId: LoginId, propertyId: Long)

    /** 사용자의 찜 목록을 `wishedAt` 내림차순으로 페이지 조회한다. 사용자가 없으면 빈 목록. */
    fun findByUserId(loginId: LoginId, page: Int, size: Int): List<WishlistModel>
}
