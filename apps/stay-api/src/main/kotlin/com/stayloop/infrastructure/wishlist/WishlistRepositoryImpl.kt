package com.stayloop.infrastructure.wishlist

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.wishlist.WishlistId
import com.stayloop.domain.wishlist.WishlistModel
import com.stayloop.domain.wishlist.WishlistRepository
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.slf4j.LoggerFactory
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
 * - `save` — `NOT_FOUND`. 메시지는 일반화 (`"사용자가 존재하지 않습니다."`) — `customMessage` 가 응답으로
 *   직행하므로 `LoginId` 를 박지 않는다 (verify-code §12 / §18). 식별자 필요 시 서버 로그에만.
 * - `deleteBy` — silent noop. `findById(...).ifPresent { delete(it) }` 1회 SELECT (verify-code §17 — 부재 허용
 *   가드가 쿼리 횟수를 늘리지 않게).
 */
@Component
class WishlistRepositoryImpl(
    private val jpa: WishlistJpaRepository,
    private val users: UserRepository,
) : WishlistRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun existsBy(userId: LoginId, propertyId: Long): Boolean {
        val resolved = users.findByLoginId(userId)?.id ?: return false
        return jpa.existsById(WishlistId(resolved, propertyId))
    }

    override fun save(userId: LoginId, propertyId: Long, wishedAt: LocalDateTime): WishlistModel {
        val resolved = users.findByLoginId(userId)
            ?: run {
                log.warn("Wishlist save 실패 — 사용자 미존재. loginId={}", userId.value)
                throw CoreException(ErrorType.NOT_FOUND, USER_NOT_FOUND_MESSAGE)
            }
        val model = WishlistModel.create(resolved.id, propertyId, wishedAt)
        return jpa.save(model)
    }

    override fun deleteBy(userId: LoginId, propertyId: Long) {
        val resolved = users.findByLoginId(userId)?.id ?: return
        // findById → ifPresent → delete 1회 SELECT — existsById + deleteById 의 2회 SELECT 회피 (§17).
        jpa.findById(WishlistId(resolved, propertyId)).ifPresent { jpa.delete(it) }
    }

    override fun findByUserId(userId: LoginId, page: PageQuery): List<WishlistModel> {
        if (page.sort.isNotEmpty()) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "Wishlist 목록은 최근 찜 순으로 고정 정렬되며, 사용자 정의 정렬을 지원하지 않습니다.",
            )
        }
        val resolved = users.findByLoginId(userId)?.id ?: return emptyList()
        val pageable = PageRequest.of(page.page, page.size, Sort.by(Sort.Direction.DESC, "wishedAt"))
        return jpa.findByUserId(resolved, pageable).content
    }

    companion object {
        private const val USER_NOT_FOUND_MESSAGE = "사용자가 존재하지 않습니다."
    }
}
