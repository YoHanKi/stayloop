package com.stayloop.application.wishlist

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.property.PropertyRepository
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.wishlist.WishlistRepository
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 찜 토글 (시퀀스 4) Facade. (`docs/design/02-sequence-diagram.md §4`, `docs/plan/week2-3.md §⑧ Phase B`)
 *
 * **트랜잭션 정책** — `wish` / `unwish` 는 *읽기-쓰기* (`@Transactional`), `getMyWishes` 는
 * `readOnly = true`. wishCount 캐시 갱신은 같은 TX 안에서 일어나야 한다.
 *
 * **멱등 처리** (AC-6):
 * - 이미 찜된 Property 에 `wish` 재호출 → noop (`wished = true` 반환, wishCount 불변)
 * - 찜되지 않은 Property 에 `unwish` 호출 → noop (`wished = false` 반환, wishCount 불변)
 * - 도메인 모델 `Property.decrementWishCount` 가 `wishCount > 0` 가드를 가지므로 멱등 흐름이 0 미만 진입 차단
 *
 * **본인 자원 인가** (AC-7) — `getMyWishes(loginId, targetUserId)` 가 *path 의 userId* 와 *헤더의 loginId* 를
 * 비교해 일치하지 않으면 FORBIDDEN. 본 Facade 는 `LoginId` 만 들고 다니므로 path/header 모두 LoginId 로
 * 비교한다 (실 사용자식별은 `users.id` BIGINT 가 아니라 LoginId 값).
 *
 * **wishCount 동시 증감 정합성** 은 4주차 동시성 영역 — 본 라운드는 단순 read-modify-write.
 *
 * **Read-then-Write SELECT 중복 — 의식적 trade-off (verify-code §17)**
 *
 * `wish` 흐름은 `existsBy` (1+1 SELECT) → `save` (1 SELECT + INSERT) 로 사용자 행 SELECT 가 2회 발생한다
 * (`WishlistRepositoryImpl` 이 `LoginId → users.id` 변환을 위해 `UserRepository.findByLoginId` 를 두 번 호출).
 * `unwish` 도 `existsBy` → `deleteBy` 로 동일.
 *
 * 단일 쿼리로 통합하려면 두 가지 길이 있다:
 * 1. `WishlistRepository.save` 시그니처에 *이미 변환된 `users.id`* 를 주입 — 도메인 boundary (LoginId 캡슐화) 훼손.
 *    Facade 가 BIGINT 를 들고 다니게 되어 `feature/wishlist` PR #6 의 결정과 충돌.
 * 2. JPA L2 cache 또는 transaction-scoped 사용자 캐시 도입 — 4주차 캐시 영역.
 *
 * **본 라운드 결정**: 단순한 read-modify-write 를 유지하고 SELECT 중복은 4주차 동시성/캐시 라운드에서
 * 함께 다룬다. 단일 사용자 단발 토글 시나리오의 운영 부하는 낮으며, 4주차에 wishCount 동시 증감 정합성
 * (`Property.wishCount` race window) 과 같이 보는 것이 합당.
 */
@Service
class WishlistFacade(
    private val wishlistRepository: WishlistRepository,
    private val propertyRepository: PropertyRepository,
    private val clock: Clock,
) {
    /**
     * 숙소 찜 등록. 이미 찜된 경우 noop (멱등, AC-6).
     */
    @Transactional
    fun wish(loginId: LoginId, propertyId: Long): WishlistToggleInfo {
        val property = propertyRepository.findById(propertyId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 숙소입니다.")
        if (wishlistRepository.existsBy(loginId, propertyId)) {
            return WishlistToggleInfo(propertyId = propertyId, wished = true, wishCount = property.wishCount)
        }
        wishlistRepository.save(loginId, propertyId, LocalDateTime.now(clock))
        property.incrementWishCount()
        propertyRepository.save(property)
        return WishlistToggleInfo(propertyId = propertyId, wished = true, wishCount = property.wishCount)
    }

    /**
     * 숙소 찜 취소. 찜되지 않은 경우 noop (멱등, AC-6).
     */
    @Transactional
    fun unwish(loginId: LoginId, propertyId: Long): WishlistToggleInfo {
        val property = propertyRepository.findById(propertyId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 숙소입니다.")
        if (!wishlistRepository.existsBy(loginId, propertyId)) {
            return WishlistToggleInfo(propertyId = propertyId, wished = false, wishCount = property.wishCount)
        }
        wishlistRepository.deleteBy(loginId, propertyId)
        property.decrementWishCount()
        propertyRepository.save(property)
        return WishlistToggleInfo(propertyId = propertyId, wished = false, wishCount = property.wishCount)
    }

    /**
     * 사용자의 찜 목록 조회. 본인 자원 인가 — path 의 `targetUserId` 와 헤더의 `loginId` 가 다르면 FORBIDDEN
     * (AC-7). 정렬은 `wishedAt DESC` 고정 (`page.sort` 비어있어야 함).
     */
    @Transactional(readOnly = true)
    fun getMyWishes(loginId: LoginId, targetUserId: LoginId, page: PageQuery): List<WishlistItemInfo> {
        if (loginId != targetUserId) {
            throw CoreException(ErrorType.FORBIDDEN, "본인 찜 목록만 조회할 수 있습니다.")
        }
        val wishes = wishlistRepository.findByUserId(loginId, page)
        if (wishes.isEmpty()) return emptyList()
        // 한 번의 batch 조회로 N+1 회피 — Property 조회는 페이지 size 만큼만 1회.
        val properties = propertyRepository.findAllByIds(wishes.map { it.propertyId }).associateBy { it.id }
        return wishes.mapNotNull { wish ->
            properties[wish.propertyId]?.let { WishlistItemInfo.of(wish, it) }
        }
    }
}
