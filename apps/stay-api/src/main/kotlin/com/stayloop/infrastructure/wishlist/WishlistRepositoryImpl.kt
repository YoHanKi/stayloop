package com.stayloop.infrastructure.wishlist

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.wishlist.WishlistId
import com.stayloop.domain.wishlist.WishlistModel
import com.stayloop.domain.wishlist.WishlistRepository
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 도메인 `WishlistRepository` 의 인프라 어댑터. JpaRepository 위임 + `LoginId ↔ users.id` 변환.
 *
 * **변환 책임**: 도메인 boundary 의 `LoginId` 는 본 클래스에서만 `users.id` (Long) 로 풀린다 —
 * 도메인 / Facade 호출자는 BIGINT 를 모른다 (`docs/plan/week2-3.md §⑥`).
 *
 * 누락 사용자 정책:
 * - `existsBy` / `findByUserId` — silent empty (저장된 행이 없는 것과 같다)
 * - `save` — `NOT_FOUND` (boundary 에서 LoginId 를 받았으나 매핑되는 사용자가 없는 사고 케이스)
 * - `deleteBy` — silent noop (삭제할 행 자체가 없음)
 */
@Component
class WishlistRepositoryImpl(
    private val jpa: WishlistJpaRepository,
    private val users: UserRepository,
) : WishlistRepository {

    override fun existsBy(userId: LoginId, propertyId: Long): Boolean {
        val resolved = users.findByLoginId(userId)?.id ?: return false
        return jpa.existsById(WishlistId(resolved, propertyId))
    }

    override fun save(userId: LoginId, propertyId: Long, wishedAt: LocalDateTime): WishlistModel {
        val resolved = users.findByLoginId(userId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "사용자가 존재하지 않습니다: ${userId.value}")
        val model = WishlistModel.create(resolved.id, propertyId, wishedAt)
        return jpa.save(model)
    }

    override fun deleteBy(userId: LoginId, propertyId: Long) {
        val resolved = users.findByLoginId(userId)?.id ?: return
        val key = WishlistId(resolved, propertyId)
        // existsById 체크로 EmptyResultDataAccessException 회피 — JPA 기본 deleteById 는 부재 행 삭제 시 던진다.
        if (jpa.existsById(key)) {
            jpa.deleteById(key)
        }
    }

    override fun findByUserId(userId: LoginId, page: PageQuery): List<WishlistModel> {
        val resolved = users.findByLoginId(userId)?.id ?: return emptyList()
        val pageable = PageRequest.of(page.page, page.size, Sort.by(Sort.Direction.DESC, "wishedAt"))
        return jpa.findByUserId(resolved, pageable).content
    }
}
